package com.webank.wecross.network.p2p;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.webank.wecross.common.NetworkQueryStatus;
import com.webank.wecross.exception.WeCrossException;
import com.webank.wecross.network.NetworkCallback;
import com.webank.wecross.network.NetworkMessage;
import com.webank.wecross.network.NetworkProcessor;
import com.webank.wecross.network.NetworkResponse;
import com.webank.wecross.network.p2p.netty.common.Node;
import com.webank.wecross.peer.Peer;
import com.webank.wecross.peer.PeerInfoMessageData;
import com.webank.wecross.peer.PeerManager;
import com.webank.wecross.peer.PeerSeqMessageData;
import com.webank.wecross.resource.Resource;
import com.webank.wecross.restserver.Versions;
import com.webank.wecross.routine.RoutineManager;
import com.webank.wecross.routine.htlc.HTLCManager;
import com.webank.wecross.stub.Connection;
import com.webank.wecross.stub.Path;
import com.webank.wecross.stub.Request;
import com.webank.wecross.stub.Response;
import com.webank.wecross.zone.ChainInfo;
import com.webank.wecross.zone.ZoneManager;
import java.util.Map;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class P2PProcessor implements NetworkProcessor {
    private Logger logger = LoggerFactory.getLogger(P2PProcessor.class);

    private PeerManager peerManager;
    private ZoneManager zoneManager;
    private P2PService p2PService;
    private RoutineManager routineManager;
    private ObjectMapper objectMapper = new ObjectMapper();

    public PeerManager getPeerManager() {
        return peerManager;
    }

    public void setPeerManager(PeerManager peerManager) {
        this.peerManager = peerManager;
    }

    public ZoneManager getZoneManager() {
        return zoneManager;
    }

    public void setZoneManager(ZoneManager networkManager) {
        this.zoneManager = networkManager;
    }

    public P2PService getP2PService() {
        return p2PService;
    }

    public void setP2PService(P2PService p2PService) {
        this.p2PService = p2PService;
    }

    public RoutineManager getRoutineManager() {
        return routineManager;
    }

    public void setRoutineManager(RoutineManager routineManager) {
        this.routineManager = routineManager;
    }

    public NetworkResponse<Object> onStatusMessage(
            Peer peerInfo, String method, String p2pRequestString) {

        NetworkResponse<Object> response = new NetworkResponse<Object>();
        response.setVersion(Versions.currentVersion);
        response.setErrorCode(NetworkQueryStatus.SUCCESS);
        response.setMessage(NetworkQueryStatus.getStatusMessage(NetworkQueryStatus.SUCCESS));

        logger.debug("request string: {}", p2pRequestString);

        try {
            switch (method) {
                case "requestPeerInfo":
                    {
                        logger.debug("Receive requestPeerInfo from peer {}", method, peerInfo);
                        NetworkMessage<Object> p2pRequest =
                                objectMapper.readValue(
                                        p2pRequestString,
                                        new TypeReference<NetworkMessage<Object>>() {});

                        p2pRequest.checkP2PMessage(method);

                        Map<String, ChainInfo> chains = zoneManager.getAllChainsInfo(true);

                        PeerInfoMessageData data = new PeerInfoMessageData();
                        data.setSeq(zoneManager.getSeq());
                        data.setChainInfos(chains);

                        response.setErrorCode(NetworkQueryStatus.SUCCESS);
                        response.setMessage("request " + method + " success");
                        response.setSeq(p2pRequest.getSeq());
                        response.setData(data);
                        break;
                    }
                case "seq":
                    {
                        logger.debug("Receive seq from peer:{}", peerInfo);
                        NetworkMessage<PeerSeqMessageData> p2pRequest =
                                objectMapper.readValue(
                                        p2pRequestString,
                                        new TypeReference<NetworkMessage<PeerSeqMessageData>>() {});

                        PeerSeqMessageData data = (PeerSeqMessageData) p2pRequest.getData();
                        if (data != null && p2pRequest.getMethod().equals("seq")) {
                            int currentSeq = data.getSeq();
                            if (peerManager.hasPeerChanged(peerInfo.getNode(), currentSeq)) {
                                NetworkMessage<Object> msg = new NetworkMessage<>();
                                msg.newSeq();

                                msg.setData(null);
                                msg.setVersion(Versions.currentVersion);
                                msg.setMethod("requestPeerInfo");

                                logger.debug(
                                        "Request peerInfo to peer:{}, seq:{}",
                                        peerInfo,
                                        msg.getSeq());

                                NetworkCallback<PeerInfoMessageData> callback =
                                        new NetworkCallback<PeerInfoMessageData>() {
                                            @Override
                                            public void onResponse(
                                                    int status,
                                                    String message,
                                                    NetworkResponse<PeerInfoMessageData>
                                                            responseMsg) {
                                                logger.trace("Receive peerInfo:{}", peerInfo);
                                                try {
                                                    if (responseMsg != null
                                                            && responseMsg.getData() != null) {

                                                        PeerInfoMessageData data =
                                                                (PeerInfoMessageData)
                                                                        responseMsg.getData();
                                                        int newSeq = data.getSeq();
                                                        if (peerManager.hasPeerChanged(
                                                                peerInfo.getNode(), newSeq)) {

                                                            // compare and update
                                                            Map<String, ChainInfo> newChains =
                                                                    data.getChainInfos();

                                                            // update zonemanager
                                                            boolean changed = false;
                                                            changed |=
                                                                    zoneManager.removeRemoteChains(
                                                                            peerInfo,
                                                                            peerInfo
                                                                                    .getChainInfos(),
                                                                            false);
                                                            changed |=
                                                                    zoneManager.addRemoteChains(
                                                                            peerInfo, newChains);
                                                            if (changed) {
                                                                logger.debug(
                                                                        "Update peerInfo from {}, seq:{}, resource:{}",
                                                                        peerInfo,
                                                                        newSeq,
                                                                        newChains);
                                                                peerInfo.setChainInfos(
                                                                        newSeq, newChains);
                                                            }
                                                        } else {
                                                            logger.debug(
                                                                    "Peer info not changed, seq:{}",
                                                                    newSeq);
                                                        }
                                                    } else {
                                                        logger.warn(
                                                                "Receive unrecognized seq message("
                                                                        + responseMsg
                                                                        + ") from peer:"
                                                                        + peerInfo);
                                                    }
                                                } catch (WeCrossException e) {
                                                    logger.error(
                                                            "Update peerInfo error({}): {}",
                                                            e.getErrorCode(),
                                                            e);
                                                } catch (Exception e) {
                                                    logger.error(
                                                            "Update peerInfo error(Internal error): {}",
                                                            e);
                                                }
                                            }
                                        };
                                callback.setTypeReference(
                                        new TypeReference<
                                                NetworkResponse<PeerInfoMessageData>>() {});

                                p2PService.asyncSendMessage(peerInfo, msg, callback);
                            }
                        } else {
                            logger.warn("Receive unrecognized seq message from peer:" + peerInfo);
                        }

                        break;
                    }
                default:
                    {
                        logger.debug("request method: " + method);
                        NetworkMessage<Object> p2pRequest =
                                objectMapper.readValue(
                                        p2pRequestString,
                                        new TypeReference<NetworkMessage<Object>>() {});
                        response.setErrorCode(NetworkQueryStatus.METHOD_ERROR);
                        response.setSeq(p2pRequest.getSeq());
                        response.setMessage("Unsupported method: " + method);
                        break;
                    }
            }

        } catch (WeCrossException e) {
            logger.warn("Process request error: {}", e.getMessage());
            response.setErrorCode(NetworkQueryStatus.EXCEPTION_FLAG + e.getErrorCode());
            response.setMessage(e.getMessage());
        } catch (Exception e) {
            logger.warn("Process request error:", e);

            response.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
            response.setMessage(e.getMessage());
        }

        logger.trace("Response " + response);
        return response;
    }

    public void onTransactionMessage(
            String network,
            String chain,
            String resource,
            String method,
            String p2pRequestString,
            NetworkProcessor.Callback callback) {
        Path path = new Path();
        path.setZone(network);
        path.setChain(chain);
        path.setResource(resource);

        NetworkResponse<Object> networkResponse = new NetworkResponse<Object>();
        networkResponse.setVersion(Versions.currentVersion);
        networkResponse.setErrorCode(NetworkQueryStatus.SUCCESS);
        networkResponse.setMessage(NetworkQueryStatus.getStatusMessage(NetworkQueryStatus.SUCCESS));

        logger.debug("request string: {}", p2pRequestString);

        try {
            // Resource resourceObj = zoneManager.getResource(path);
            Resource resourceObj = zoneManager.fetchResource(path);
            if (resourceObj == null) {
                logger.warn("Unable to find resource: {}.{}.{}", network, chain, resource);
                throw new Exception("Resource not found");
            } else {
                HTLCManager htlcManager = routineManager.getHtlcManager();
                resourceObj = htlcManager.filterHTLCResource(zoneManager, path, resourceObj);
            }

            switch (method) {
                case "transaction":
                    {
                        logger.debug("On remote transaction request");
                        NetworkMessage<Request> p2pRequest =
                                objectMapper.readValue(
                                        p2pRequestString,
                                        new TypeReference<NetworkMessage<Request>>() {});
                        p2pRequest.checkP2PMessage(method);

                        resourceObj.onRemoteTransaction(
                                p2pRequest.getData(),
                                new Connection.Callback() {
                                    @Override
                                    public void onResponse(Response response) {
                                        networkResponse.setData(response);
                                        networkResponse.setSeq(p2pRequest.getSeq());
                                        String responseContent = new String();
                                        try {
                                            responseContent =
                                                    objectMapper.writeValueAsString(
                                                            networkResponse);
                                        } catch (Exception e) {
                                            logger.warn("Process request error:", e);
                                            NetworkResponse<Object> errorResponse =
                                                    new NetworkResponse<Object>();
                                            errorResponse.setSeq(p2pRequest.getSeq());
                                            errorResponse.setErrorCode(
                                                    NetworkQueryStatus.INTERNAL_ERROR);
                                            errorResponse.setMessage(e.getLocalizedMessage());
                                            try {
                                                responseContent =
                                                        objectMapper.writeValueAsString(
                                                                errorResponse);
                                            } catch (Exception e1) {
                                                logger.error(
                                                        "Can't serialize error response: "
                                                                + resource.toString());
                                            }
                                        }
                                        callback.onResponse(responseContent);
                                    }
                                });

                        return;
                    }
                default:
                    {
                        NetworkMessage<Object> p2pRequest =
                                objectMapper.readValue(
                                        p2pRequestString,
                                        new TypeReference<NetworkMessage<Object>>() {});
                        logger.warn("Unsupported method: {}", method);
                        networkResponse.setErrorCode(NetworkQueryStatus.METHOD_ERROR);
                        networkResponse.setMessage("Unsupported method: " + method);
                        networkResponse.setSeq(p2pRequest.getSeq());
                        break;
                    }
            }
        } catch (WeCrossException e) {
            logger.warn("Process request error: {}", e.getMessage());
            networkResponse.setErrorCode(NetworkQueryStatus.EXCEPTION_FLAG + e.getErrorCode());
            networkResponse.setMessage(e.getMessage());
        } catch (Exception e) {
            logger.warn("Process request error:", e);

            networkResponse.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
            networkResponse.setMessage(e.getLocalizedMessage());
        }

        try {
            callback.onResponse(objectMapper.writeValueAsString(networkResponse));

        } catch (Exception e1) {
            logger.error("Can't serialize error response: " + resource.toString());
        }
    }

    @Override
    public void process(Node node, String content, NetworkProcessor.Callback callback) {
        NetworkMessage<?> networkMessage = new NetworkMessage<>();
        try {
            networkMessage = objectMapper.readValue(content, NetworkMessage.class);

            String method = networkMessage.getMethod();
            String r[] = method.split("/");

            Peer peerInfo = peerManager.getPeerInfo(node);

            if (r.length == 1) {
                /** method */
                NetworkResponse<Object> networkResponse = onStatusMessage(peerInfo, r[0], content);
                callback.onResponse(objectMapper.writeValueAsString(networkResponse));
            } else if (r.length == 4) {
                /** network/stub/resource/method */
                onTransactionMessage(r[0], r[1], r[2], r[3], content, callback);
            } else {
                throw new Exception("invalid method parameter, method: " + method);
            }

        } catch (Exception e) {
            logger.error(" invalid format, host: {}, e: {}", node, e);
            NetworkResponse<Object> networkResponse = new NetworkResponse<>();
            networkResponse.setMessage(e.getMessage());
            networkResponse.setErrorCode(NetworkQueryStatus.INTERNAL_ERROR);
            networkResponse.setSeq(networkMessage.getSeq());
            networkResponse.setVersion(networkMessage.getVersion());
            try {
                callback.onResponse(objectMapper.writeValueAsString(networkResponse));

            } catch (Exception e1) {
                logger.error("writeValueAsString exception: {}, e: {}", node, e1);
            }
        }
    }
}
