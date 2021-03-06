package com.mcode.gateway.rpc;

import com.mcode.gateway.Bootstrap;
import com.mcode.gateway.LoadOrder;
import com.mcode.gateway.configuration.CommonConfig;
import com.mcode.gateway.configuration.MqConfig;
import com.mcode.gateway.dispatch.CallbackManager;
import com.mcode.gateway.dispatch.ClusterManager;
import com.mcode.gateway.dispatch.event.EventHandler;
import com.mcode.gateway.rpc.serialization.Trans;
import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.DefaultConsumer;
import com.rabbitmq.client.Envelope;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.RecoveryListener;
import com.rabbitmq.client.ShutdownSignalException;
import lombok.extern.slf4j.Slf4j;
import org.apache.commons.lang3.StringUtils;
import org.springframework.stereotype.Component;

import javax.annotation.Resource;
import java.io.IOException;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.Queue;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.LinkedBlockingDeque;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.function.Consumer;

@Component
@Slf4j
@LoadOrder(value = 5)
public class MqConnector implements Bootstrap {
    @Resource
    private MqConfig mqConfig;
    @Resource
    private CommonConfig commonConfig;
    @Resource
    private ClusterManager clusterManager;
    @Resource
    private CallbackManager callbackManager;
    private Queue<PublishEvent> publishQueue = new ArrayBlockingQueue<>(100);
    private ExecutorService publisherFactory = new ThreadPoolExecutor(1,
            1,
            0,
            TimeUnit.MILLISECONDS,
            new LinkedBlockingDeque<>(200), r -> {
        Thread thread = new Thread(r);
        thread.setName("publish-exec-1");
        return thread;
    });
    private static Connection connection;
    private static String QUEUE_MODEL = "direct";
    private final Object lock = new Object();
    private static final String EQUIPMENT_QUEUE = "equipment_type_";
    public static final String DISPATCHER_ID = "dispatcherId";
    public static final String CONNECTOR_ID = "connectorId";
    private static Map<Integer, String> queueMap = new ConcurrentHashMap<>();
    private static volatile boolean hasConnected = false;

    private void connectRabbitMq() throws IOException, TimeoutException {
        ConnectionFactory factory = new ConnectionFactory();
        factory.useNio();
        factory.setAutomaticRecoveryEnabled(true);
        factory.setNetworkRecoveryInterval(5000);
        factory.setHost(mqConfig.getMqHost());
        factory.setPort(mqConfig.getMqPort());
        factory.setUsername(mqConfig.getMqUserName());
        factory.setPassword(mqConfig.getMqPwd());
        factory.setVirtualHost(StringUtils.isBlank(mqConfig.getVirtualHost()) ? "/" : mqConfig.getVirtualHost());
        connection = factory.newConnection();
        //adding a ShutdownListener to an object that is already closed will fire the listener immediately
        connection.addShutdownListener(e -> {
            log.error("rabbitMq断开连接：{} \r\n location:{}",
                    Arrays.toString(e.getStackTrace()), e.getReason().protocolMethodName());
            hasConnected = false;
        });
        Recoverable recoverable = (Recoverable) MqConnector.connection;
        recoverable.addRecoveryListener(new RecoveryListener() {
            @Override
            public void handleRecovery(Recoverable recoverable) {
                log.warn("rabbitMq完成自动连接");
                hasConnected = true;
            }

            @Override
            public void handleRecoveryStarted(Recoverable recoverable) {
                log.warn("rabbitMq_准备开始重连");
            }
        });
        hasConnected = true;
    }

    private void registryConsumer() throws IOException {
        String upQueueName = mqConfig.getUpQueueName();
        //消费者不关心exchange和queue的binding，声明关注的队列即可
        for (int i = 0; i < 2; i++) {
            Channel channel = connection.createChannel();
            channel.queueDeclare(upQueueName, true, false, false, null);
            channel.basicConsume(upQueueName, true, "", new DefaultConsumer(channel) {
                @Override
                public void handleDelivery(String consumerTag, Envelope envelope,
                                           AMQP.BasicProperties properties, byte[] body) throws IOException {
                    Object dispatcherId;
                    Map<String, Object> headers = properties.getHeaders();
                    if (headers == null || (dispatcherId = headers.get(DISPATCHER_ID)) == null) {
                        clusterManager.publish(commonConfig.getDispatcherId(), body);
                    } else {
                        clusterManager.publish(dispatcherId.toString(), body);
                    }
                }
            });
        }
    }

    /**
     * 根据设备类型获取设备队列名
     *
     * @return Stirng
     */
    public String getQueue(Integer eqType) {
        return queueMap.computeIfAbsent(eqType, t -> EQUIPMENT_QUEUE + t);
    }

    /**
     * 与dispatcher同步通信
     * 同步发送消息需要注册一个eventHandler事件处理器，继承SyncEventHandler，并通过setEventType添加事件类型（枚举中定义）
     * 这个eventHandler将作为异步——同步的桥梁传递事件，dispatcher端响应对的结果将通过eventHandler同步返回
     * 若不注册eventHandler，则会导致无法接收到事件，同步调用超时
     * 回调流程详见 {@link EventHandler}
     *
     * @param publishEvent 推送参数
     */
    public Trans.event_data publishSync(PublishEvent publishEvent, long blockingTime) {
        SyncWarpper warpper = new SyncWarpper();
        Consumer<Trans.event_data> consumerProxy = warpper.mockCallback();
        callbackManager.registerCallbackEvent(publishEvent.getSerialNumber(), consumerProxy);
        publishAsync(publishEvent);
        return warpper.blockingResult(blockingTime);
    }

    /**
     * 与dispatcher异步通信
     */
    public void publishAsync(PublishEvent publishEvent) {
        synchronized (lock) {
            if (publishQueue.offer(publishEvent)) {
                lock.notify();
            } else {
                log.warn("发送消息队列已满，检查publisher线程是否存活");
            }
        }
    }

    @Override
    public void init() {
        //rabbitMq有自动重连机制，但不会在client初始化时生效，故需手动重连
        //且它的自动重连仅针对connection，channel没用
        try {
            connectRabbitMq();
        } catch (TimeoutException | IOException e) {
            log.error("连接rabbitmq超时！尝试进行重连");
            hasConnected = false;
            while (!hasConnected) {
                try {
                    Thread.sleep(5000);
                } catch (InterruptedException e1) {
                    //do nothing
                }
                try {
                    connectRabbitMq();
                } catch (IOException | TimeoutException e1) {
                    log.error("连接rabbitMq失败，5s后重试");
                }
            }
        }
        try {
            registryConsumer();
        } catch (IOException e) {
            log.error("注册consumer IO异常，尝试重新注册");
            try {
                registryConsumer();
            } catch (IOException e1) {
                throw new RuntimeException("无法注册rabbitMq消费者" + Arrays.toString(e1.getStackTrace()));
            }
        }
        startPublishThread();
    }

    /**
     * 由于channel非线程安全，把所有publish的操作放到一条线程处理
     */
    private void startPublishThread() {
        publisherFactory.execute(new Runnable() {
            private Map<String, Channel> routingChannel = new HashMap<>();
            private boolean runnable = true;

            @Override
            public void run() {
                while (runnable) {
                    PublishEvent eventEntry;
                    synchronized (lock) {
                        while ((eventEntry = publishQueue.poll()) == null) {
                            try {
                                lock.wait();
                            } catch (InterruptedException e) {
                                e.printStackTrace();
                            }
                        }
                        adaptChannel(eventEntry);
                    }
                }
            }

            /**
             * 根据routingKey获取相应的channel，分发消息给不同设备的队列
             */
            private void adaptChannel(PublishEvent eventEntry) {
                String queue = eventEntry.getQueue();
                Channel channel;
                if ((channel = routingChannel.get(queue)) != null) {
                    publish(eventEntry, channel);
                } else {
                    Channel newChannel = newProducerChannel(queue);
                    if (newChannel != null) {
                        routingChannel.put(queue, newChannel);
                        publish(eventEntry, newChannel);
                    }
                }
            }

            /**
             * 推送给mq
             */
            private void publish(PublishEvent eventEntry, Channel localChannel) {
                String routingKey = eventEntry.getQueue();
                byte[] message = eventEntry.getMessage();
                Map<String, Object> headers = eventEntry.getHeaders();
                String exchangeName = mqConfig.getExchangeName();
                try {
                    //暂不持久化消息
                    AMQP.BasicProperties props = new AMQP.BasicProperties().
                            builder().
                            deliveryMode(1).
                            headers(headers).
                            build();
                    localChannel.basicPublish(exchangeName, routingKey, false, props, message);
                } catch (IOException | ShutdownSignalException e) {
                    log.error("消息发送失败,等待mq重连：{}",Arrays.toString(e.getStackTrace()));
                }
            }

            /**
             * 生产者channel
             * channel并非线程安全，共用一个channel可能导致autoACK出现问题
             *
             * @return Channel
             */

            private Channel newProducerChannel(String queue) {
                try {
                    log.info(queue);
                    Channel channel = connection.createChannel();
                    String exchangeName = mqConfig.getExchangeName();
                    //direct模式、持久化交换机
                    channel.exchangeDeclare(exchangeName, QUEUE_MODEL, true);
                    //声明持久化、非排他、非自动删除队列,设置队列消息过期时间
                    Map<String, Object> param = new HashMap<>();
//                    param.put("x-message-ttl", 40000);
                    channel.queueDeclare(queue, true, false, false, param);
                    //绑定队列到交换机,queue名做routingKey
                    channel.queueBind(queue, exchangeName, queue);
                    return channel;
                } catch (IOException | ShutdownSignalException e1) {
                    //若connection已关闭
                    log.error("rabbitMql连接已关闭，无法创建生产者channel，等待重新连接");
                    e1.printStackTrace();
                    runnable = false;
                    return null;
                }
            }
        });
    }

    /**
     * 消息同步器
     */
    private class SyncWarpper {
        private volatile Trans.event_data eventEntry;
        private CountDownLatch latch = new CountDownLatch(1);
        private long current = System.currentTimeMillis();

        public Trans.event_data blockingResult(long timeout) {
            try {
                boolean await = latch.await(timeout, TimeUnit.MILLISECONDS);
                if (!await) {
                    log.warn("同步调用超时，检查mq连接状态");
                    return null;
                }
            } catch (InterruptedException e) {
                log.warn("同步调用线程被中断,{}", e);
                return null;
            }
            log.info("同步调用返回结果:{},耗时：{}", eventEntry, System.currentTimeMillis() - current);
            return eventEntry;
        }

        public Consumer<Trans.event_data> mockCallback() {
            return eventEntry -> {
                this.eventEntry = eventEntry;
                latch.countDown();
            };
        }
    }

}
