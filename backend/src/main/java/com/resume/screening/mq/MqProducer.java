package com.resume.screening.mq;

import com.resume.screening.common.Json;
import com.resume.screening.config.AppProperties;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.producer.DefaultMQProducer;
import org.apache.rocketmq.common.message.Message;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.nio.charset.StandardCharsets;

/**
 * MQ 生产者。两个关键约定：
 * 1) 只在事务提交后投递（需求 §7.3）——避免"消息发出去了但库里回滚了"；
 * 2) 投递失败不阻塞用户请求，只记 ERROR，由 ReconcileJob 兜底重投。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MqProducer implements InitializingBean {

    private final AppProperties props;
    private final Json json;

    private volatile DefaultMQProducer producer;
    private volatile boolean available;

    @Override
    public void afterPropertiesSet() {
        if (!props.getMq().isEnabled()) {
            log.warn("RocketMQ 已禁用（app.mq.enabled=false），异步链路不会启动");
            return;
        }
        DefaultMQProducer p = new DefaultMQProducer(props.getMq().getProducerGroup());
        p.setNamesrvAddr(props.getMq().getNamesrvAddr());
        p.setSendMsgTimeout(5000);
        p.setRetryTimesWhenSendFailed(2);
        p.setVipChannelEnabled(false);
        try {
            p.start();
            this.producer = p;
            this.available = true;
            log.info("RocketMQ 生产者启动成功 group={} namesrv={}",
                    props.getMq().getProducerGroup(), props.getMq().getNamesrvAddr());
        } catch (Exception e) {
            log.error("RocketMQ 生产者启动失败，异步链路降级为等待重投：{}", e.getMessage());
            try {
                p.shutdown();
            } catch (Exception ignore) {
                // 启动都失败了，关闭异常无需关注
            }
        }
    }

    public boolean isAvailable() {
        return available;
    }

    public void send(String topic, String key, Object payload) {
        DefaultMQProducer p = this.producer;
        if (p == null) {
            throw new IllegalStateException("RocketMQ 生产者未就绪");
        }
        byte[] body = json.write(payload).getBytes(StandardCharsets.UTF_8);
        Message message = new Message(topic, "*", key, body);
        try {
            p.send(message);
            log.info("消息已投递 topic={} key={}", topic, key);
        } catch (Exception e) {
            throw new IllegalStateException("消息投递失败 topic=" + topic + " key=" + key, e);
        }
    }

    /**
     * 事务提交后投递。没有活动事务时立即投递。
     * 投递异常被吞掉并打 ERROR——上传接口不能因为 MQ 抖动而失败。
     */
    public void sendAfterCommit(String topic, String key, Object payload) {
        Runnable task = () -> {
            try {
                send(topic, key, payload);
            } catch (Exception e) {
                log.error("事务提交后投递失败，等待对账任务重投 topic={} key={} err={}",
                        topic, key, e.getMessage());
            }
        };

        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
                @Override
                public void afterCommit() {
                    task.run();
                }
            });
        } else {
            task.run();
        }
    }

    @PreDestroy
    public void shutdown() {
        if (producer != null) {
            producer.shutdown();
            log.info("RocketMQ 生产者已关闭");
        }
    }
}
