package com.hc.dispatch;

import com.hc.Bootstrap;
import com.hc.LoadOrder;
import com.hc.configuration.CommonConfig;
import com.hc.dispatch.event.EventHandler;
import com.hc.dispatch.event.EventHandlerPipeline;
import com.hc.dispatch.event.PipelineContainer;
import com.hc.rpc.TransportEventEntry;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Consumer;

/**
 * 上行请求处理
 */
@Slf4j
@Component
@LoadOrder(value = 3)
public class MqEventUpStream implements Bootstrap {
    @Resource
    private CommonConfig commonConfig;
    @Resource
    private PipelineContainer pipelineContainer;
    private ArrayBlockingQueue<TransportEventEntry> eventQueue;
    private ExecutorService eventExecutor;
    private final Object lock = new Object();

    private void initQueue() {
        eventQueue = new ArrayBlockingQueue<>(commonConfig.getMqEventQueueSize());
        eventExecutor = Executors.newFixedThreadPool(commonConfig.getEventBusThreadNumber(), new ThreadFactory() {
            private AtomicInteger count = new AtomicInteger(1);

            @Override
            public Thread newThread(Runnable r) {
                Thread thread = new Thread(r);
                thread.setDaemon(true);
                thread.setName("event-loop-" + count.getAndIncrement());
                return thread;
            }
        });
    }

    public void handlerMessage(TransportEventEntry transportEventEntry) {
        synchronized (lock) {
            if (!eventQueue.add(transportEventEntry)) {
                log.warn("HttpUpStream事件处理队列已满");
            }
            lock.notify();
        }
    }

    /**
     * eventLoop单线程，纯内存操作目无须修改其线程数
     * 否则一定会出现线程安全问题，如果要执行阻塞操作参考{@link EventHandler#blockingOperation(Runnable)}
     */
    @SuppressWarnings({"Duplicates", "InfiniteLoopStatement"})
    private void exeEventLoop() {
        eventExecutor.execute(() -> {
            while (true) {
                TransportEventEntry event;
                synchronized (lock) {
                    while ((event = eventQueue.poll()) == null) {
                        try {
                            lock.wait();
                        } catch (InterruptedException e) {
                            e.printStackTrace();
                        }
                    }
                }
                try {
                    Integer eventType = event.getType();
                    String serialNumber = event.getSerialNumber();
                    Consumer<TransportEventEntry> consumer;
                    EventHandlerPipeline pipeline = pipelineContainer.getPipelineBySerialId(serialNumber);
                    //若未注册pipeline，使用默认的pipeline
                    if (pipeline == null) {
                        pipeline = pipelineContainer.getDefaultPipeline();
                    }
                    if ((consumer = pipeline.adaptEventHandler(eventType)) != null) {
                        consumer.accept(event);
                        pipelineContainer.removePipeline(serialNumber);
                    } else {
                        log.warn("未经注册的事件，{}", event);
                    }
                } catch (Exception e) {
                    log.warn("事件处理异常，{}", e);
                }
            }
        });
    }

    @Override
    public void init() {
        log.info("load event queue and event poller thread");
        initQueue();
        exeEventLoop();
    }


}
