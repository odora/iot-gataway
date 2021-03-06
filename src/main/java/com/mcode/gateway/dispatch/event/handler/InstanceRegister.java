package com.mcode.gateway.dispatch.event.handler;

import com.google.gson.Gson;
import com.mcode.gateway.configuration.CommonConfig;
import com.mcode.gateway.configuration.ConfigCenter;
import com.mcode.gateway.configuration.RedisConfig;
import com.mcode.gateway.dispatch.event.AsyncEventHandler;
import com.mcode.gateway.rpc.MqConnector;
import com.mcode.gateway.rpc.NodeEntry;
import com.mcode.gateway.rpc.PublishEvent;
import com.mcode.gateway.rpc.serialization.Trans;
import com.mcode.gateway.type.EventTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;

@Slf4j
@Component
public class InstanceRegister extends AsyncEventHandler {
    @Resource
    private JedisPool jedisPool;
    @Resource
    private Gson gson;
    @Resource
    private MqConnector mqConnector;
    @Resource
    private ConfigCenter configCenter;
    @Resource
    private RedisConfig redisConfig;
    @Resource
    private CommonConfig commonConfig;

    @Override
    public void accept(Trans.event_data event) {
        String nodeArtifactId = event.getNodeArtifactId();
        String serialNumber = event.getSerialNumber();
        Integer eqType = event.getEqType();
        Integer protocol = event.getProtocol();
        validEmpty("节点项目唯一ID", nodeArtifactId);
        validEmpty("节点注册流水号", serialNumber);
        validEmpty("节点设备类型", eqType);
        validEmpty("节点设备协议", protocol);
        String eqQueueName = mqConnector.getQueue(eqType);
        //校验是否存在该类型设备
        boolean equipmentName = configCenter.existEquipmentType(eqType);
        boolean protocolRegistry = configCenter.existProtocolType(protocol);
        if (!equipmentName) {
            log.warn("该类型设备未被注册，拒绝连接，{}", event);
            publishRegisterResult(EventTypeEnum.REGISTER_ERROR.getType(),
                    "该类型设备未被注册，拒绝连接", nodeArtifactId, serialNumber, eqQueueName);
        }
        if (!protocolRegistry) {
            log.warn("不支持该协议类型，拒绝连接，{}", event);
            publishRegisterResult(EventTypeEnum.REGISTER_ERROR.getType(),
                    "不支持该协议类型，拒绝连接", nodeArtifactId, serialNumber, eqQueueName);
        }
        //注册节点，无需校验节点是否存在，心跳超时节点自动过期
        try (Jedis jeids = jedisPool.getResource()) {
            NodeEntry nodeEntry = new NodeEntry();
            nodeEntry.setEqType(eqType);
            nodeEntry.setNodeId(nodeArtifactId);
            nodeEntry.setProtocol(protocol);
            Boolean exists = jeids.exists(nodeArtifactId);
            if (exists) {
                log.warn("connector节点已登录，无法重复登陆");
                publishRegisterResult(EventTypeEnum.REGISTER_FAIL.getType(),
                        "节点已注册，无需重复注册", nodeArtifactId, serialNumber, eqQueueName);
                return;
            } else {
                jeids.setex(nodeArtifactId, redisConfig.getKeyExpire(), gson.toJson(nodeEntry));
            }
        }
        log.info("节点【{}】，设备类型:【{}】，协议类型【{}】登陆成功", nodeArtifactId, eqType, protocol);
        publishRegisterResult(EventTypeEnum.REGISTER_SUCCESS.getType(),
                StringUtils.EMPTY, nodeArtifactId, serialNumber, eqQueueName);
    }

    private void publishRegisterResult(Integer type, String msg, String nodeArtifactId,
                                       String serialNumber, String eqQueueName) {
        byte[] bytes = Trans.event_data.newBuilder().
                setType(type).
                setMsg(msg).
                setNodeArtifactId(nodeArtifactId).
                setSerialNumber(serialNumber).
                setDispatcherId(commonConfig.getDispatcherId()).
                build().toByteArray();
        PublishEvent publishEvent = new PublishEvent(eqQueueName, bytes, serialNumber);
        publishEvent.addHeaders(MqConnector.CONNECTOR_ID, nodeArtifactId);
        mqConnector.publishAsync(publishEvent);
    }

    @Override
    public Integer setEventType() {
        return EventTypeEnum.INSTANCE_REGISTER.getType();
    }
}
