package com.mcode.gateway.dispatch.event.handler;

import com.google.gson.Gson;
import com.mcode.gateway.business.dal.dao.EquipmentRegistry;
import com.mcode.gateway.business.dto.EquipmentRegisterDTO;
import com.mcode.gateway.business.service.DeviceManagementService;
import com.mcode.gateway.configuration.ConfigCenter;
import com.mcode.gateway.dispatch.event.AsyncEventHandler;
import com.mcode.gateway.dispatch.event.MapDatabase;
import com.mcode.gateway.rpc.MqConnector;
import com.mcode.gateway.rpc.PublishEvent;
import com.mcode.gateway.rpc.SessionEntry;
import com.mcode.gateway.rpc.serialization.Trans;
import com.mcode.gateway.type.EventTypeEnum;
import com.mcode.gateway.util.AsyncHttpClient;
import lombok.Getter;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.apache.http.HttpResponse;
import org.apache.http.concurrent.FutureCallback;
import org.springframework.stereotype.Component;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;

import javax.annotation.Resource;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

@Slf4j
@Component
public class DataUpload extends AsyncEventHandler {
    @Resource
    private JedisPool jedisPool;
    @Resource
    private Gson gson;
    @Resource
    private ConfigCenter configCenter;
    @Resource
    private DeviceManagementService deviceService;
    @Resource
    private MqConnector mqConnector;
    @Resource
    private MapDatabase mapDatabase;
    private static final Map<String, FailHandler> batchFail = Collections.synchronizedMap(new HashMap<>());
    public static final String RE_POST_MESSAGE = "http_backup";

    @Override
    public void accept(Trans.event_data event) {
        String eqId = event.getEqId();
        String uri = event.getUri();
        String msg = event.getMsg();
        Integer eqType = event.getEqType();
        String nodeArtifactId = event.getNodeArtifactId();
        String serialNumber = event.getSerialNumber();
        validEmpty("设备ID", eqId);
        validEmpty("上传uri", uri);
        validEmpty("上传消息体", msg);
        validEmpty("设备类型", eqType);
        validEmpty("设备节点ID", nodeArtifactId);
        validEmpty("流水号", serialNumber);
        log.info("接受设备上传的指令：{}", event);
        //验证connector是否注册,但仍然接受上传的数据
        String queue = mqConnector.getQueue(eqType);
        boolean register = validNodeRegister(nodeArtifactId);
        if (!register) {
            reConnectPush(queue, nodeArtifactId);
        }
        String uniqueId = MD5(eqType + eqId);
        try (Jedis jedis = jedisPool.getResource()) {
            String hget = jedis.hget(EquipmentLogin.SESSION_MAP, uniqueId);
            if (!StringUtils.isEmpty(hget)) {
                SessionEntry sessionEntry = gson.fromJson(hget, SessionEntry.class);
                Integer profile = sessionEntry.getProfile();
                String callbackDomain = configCenter.getProfileRegistry().get(profile);
                String url = callbackDomain + uri;
                AsyncHttpClient.sendPost(url, msg, new FailHandler(url, msg, serialNumber, mapDatabase));
            } else {
                log.warn("设备会话不在线，但能正常通信，检查数据一致");
                EquipmentRegisterDTO equipmentRegisterDTO = new EquipmentRegisterDTO();
                equipmentRegisterDTO.setUniqueId(uniqueId);
                List<EquipmentRegistry> equipments = deviceService.selectEquipmentByCondition(equipmentRegisterDTO);
                EquipmentRegistry registry = equipments.get(0);
                if (registry != null) {
                    String callbackDomain = configCenter.getProfileRegistry().get(registry.getEquipmentProfile());
                    String url = callbackDomain + uri;
                    AsyncHttpClient.sendPost(url, msg, new FailHandler(url, msg, serialNumber, mapDatabase));
                } else {
                    log.warn("该设备并未注册！，{}", event);
                }
            }
        }
        //确认上传成功
        byte[] bytes = Trans.event_data.newBuilder().
                setType(EventTypeEnum.UPLOAD_SUCCESS.getType()).
                setTimeStamp(System.currentTimeMillis()).
                setSerialNumber(serialNumber).build().toByteArray();
        PublishEvent publishEvent = new PublishEvent(mqConnector.getQueue(eqType), bytes, serialNumber);
        publishEvent.addHeaders(MqConnector.CONNECTOR_ID, nodeArtifactId);
        mqConnector.publishAsync(publishEvent);
    }

    @Override
    public Integer setEventType() {
        return EventTypeEnum.DEVICE_UPLOAD.getType();
    }

    @Getter
    public static class FailHandler implements FutureCallback<HttpResponse> {
        private String url;
        private String param;
        private String id;
        private boolean endurance = false;
        private MapDatabase mapDatabase;

        public FailHandler(String url, String param, String serialNumber, MapDatabase mapDatabase) {
            this.url = url;
            this.param = param;
            this.id = serialNumber;
            this.mapDatabase = mapDatabase;
        }

        @Override
        public void completed(HttpResponse httpResponse) {
            if (endurance) {
                mapDatabase.remove(id, RE_POST_MESSAGE);
            }
            log.info("调用 {} 接口成功,参数：{}", url, param);
        }

        @Override
        public void failed(Exception e) {
            //TODO 批量插入DB，减少大量失败时频繁连接DB的cpu消耗，但可能漏数据，需根据业务动态调整
            endurance = true;
            if (batchFail.size() == 100) {
                synchronized (batchFail) {
                    batchFail.forEach((s, failHandler) -> mapDatabase.write(id, failHandler, RE_POST_MESSAGE));
                    batchFail.clear();
                }
                mapDatabase.write(id, this, RE_POST_MESSAGE).close();
                mapDatabase.close();
            } else {
                batchFail.put(id, this);
            }
        }

        @Override
        public void cancelled() {
            log.warn("取消调用{}接口,参数：{}", url, param);
        }
    }
}
