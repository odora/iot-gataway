package com.mcode.gateway.dispatch.event.handler;

import com.google.gson.Gson;
import com.mcode.gateway.business.dal.EquipmentDAL;
import com.mcode.gateway.business.dal.dao.EquipmentRegistry;
import com.mcode.gateway.dispatch.event.AsyncEventHandler;
import com.mcode.gateway.rpc.MqConnector;
import com.mcode.gateway.rpc.PublishEvent;
import com.mcode.gateway.rpc.SessionEntry;
import com.mcode.gateway.rpc.serialization.Trans;
import com.mcode.gateway.type.EventTypeEnum;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;
import org.springframework.util.CollectionUtils;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;
import java.util.List;

@Slf4j
@Component
public class EquipmentLogin extends AsyncEventHandler {
    @Resource
    private EquipmentDAL equipmentDAL;
    @Resource
    private JedisPool jedisPool;
    @Resource
    private Gson gson;
    @Resource
    private MqConnector mqConnector;
    public static final String SESSION_MAP = "device:session";

    @Override
    public void accept(Trans.event_data event) {
        Integer eqType = event.getEqType();
        String eqId = event.getEqId();
        String nodeArtifactId = event.getNodeArtifactId();
        String serialNumber = event.getSerialNumber();
        String socketId = event.getMsg();
        validEmpty("节点ID", nodeArtifactId);
        validEmpty("设备类型", eqType);
        validEmpty("设备唯一ID", eqId);
        validEmpty("流水号", serialNumber);
        validEmpty("socketId", socketId);
        //验证connector是否注册
        String eqQueueName = mqConnector.getQueue(eqType);
        boolean register = validNodeRegister(nodeArtifactId);
        if (!register) {
            reConnectPush(eqQueueName, nodeArtifactId);
            return;
        }
        //uniqueId规则，MD5(设备类型+设备ID)
        String md5UniqueId = MD5(eqType + eqId);
        List<EquipmentRegistry> equipment = equipmentDAL.getByUniqueId(md5UniqueId);
        //设备尚未注册
        if (CollectionUtils.isEmpty(equipment)) {
            log.warn("设备登陆失败，未注册，{}", event);
            Trans.event_data.Builder response = Trans.event_data.newBuilder();
            byte[] bytes = response.setMsg(socketId + ":设备登陆失败，未注册").
                    setType(EventTypeEnum.LOGIN_FAIL.getType()).
                    setNodeArtifactId(nodeArtifactId).
                    setEqId(eqId).
                    setSerialNumber(serialNumber).
                    setTimeStamp(System.currentTimeMillis()).
                    build().toByteArray();
            publishToConnector(bytes, eqQueueName, nodeArtifactId, serialNumber);
        } else {
            //设备已注册
            Long hsetnx;
            EquipmentRegistry registry = equipment.get(0);
            try (Jedis jedis = jedisPool.getResource()) {
                SessionEntry eqSession = new SessionEntry();
                eqSession.setEqId(eqId);
                eqSession.setProfile(registry.getEquipmentProfile());
                eqSession.setEqType(registry.getEquipmentType());
                eqSession.setNode(nodeArtifactId);
                hsetnx = jedis.hsetnx(SESSION_MAP, md5UniqueId, gson.toJson(eqSession));
            }
            //有些设备长连接并不稳定，可能频繁发送登陆包，因此不做登陆校验
            if (hsetnx == 1) {
                log.info("设备类型：【{}】，ID:【{}】从【{}】节点【首次】登陆", eqType, eqId, nodeArtifactId);
            } else {
                log.info("设备类型：【{}】，ID:【{}】从【{}】节点【重复】登陆", eqType, eqId, nodeArtifactId);
//                validLogin(eqType, eqId, nodeArtifactId, serialNumber, eqQueueName);
            }
            //返回登陆成功事件，传入环境配置
            Trans.event_data.Builder response = Trans.event_data.newBuilder();
            byte[] bytes = response.setType(EventTypeEnum.LOGIN_SUCCESS.getType()).
                    setNodeArtifactId(nodeArtifactId).
                    setEqId(eqId).
                    setSerialNumber(serialNumber).
                    setTimeStamp(System.currentTimeMillis()).
                    build().toByteArray();
            publishToConnector(bytes, eqQueueName, nodeArtifactId, serialNumber);
        }
    }

    private void validLogin(Integer eqType, String eqId, String nodeArtifactId, String serialNumber, String eqQueueName) {
        Trans.event_data.Builder response = Trans.event_data.newBuilder();
        byte[] bytes = response.setMsg("设备登陆失败，设备已登陆").
                setType(EventTypeEnum.LOGIN_FAIL.getType()).
                setNodeArtifactId(nodeArtifactId).
                setEqId(eqId).
                setSerialNumber(serialNumber).
                setTimeStamp(System.currentTimeMillis()).
                build().toByteArray();
        publishToConnector(bytes, eqQueueName, nodeArtifactId, serialNumber);
    }

    /**
     * 推送给connector
     *
     * @param bytes       事件
     * @param eqQueueName 设备队列名
     */
    private void publishToConnector(byte[] bytes, String eqQueueName, String nodeArtifactId, String serialNumber) {
        PublishEvent publishEvent = new PublishEvent(eqQueueName, bytes, serialNumber);
        publishEvent.addHeaders(MqConnector.CONNECTOR_ID, nodeArtifactId);
        mqConnector.publishAsync(publishEvent);
    }

    @Override
    public Integer setEventType() {
        return EventTypeEnum.DEVICE_LOGIN.getType();
    }
}
