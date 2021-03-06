package com.mcode.gateway.dispatch.event;


import com.mcode.gateway.dispatch.CallbackManager;
import com.mcode.gateway.rpc.serialization.Trans;
import com.mcode.gateway.util.CommonUtil;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.BeansException;
import org.springframework.beans.factory.BeanFactory;

/**
 * 同步事件处理器
 */
@Slf4j
public abstract class SyncEventHandler extends CommonUtil implements EventHandler {
    private CallbackManager callbackManager;

    @Override
    public void setBeanFactory(BeanFactory beanFactory) throws BeansException {
        callbackManager = beanFactory.getBean(CallbackManager.class);
    }

    @Override
    public void accept(Trans.event_data event) {
        callbackManager.execCallback(event);
    }
}
