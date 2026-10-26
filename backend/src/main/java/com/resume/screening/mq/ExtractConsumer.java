package com.resume.screening.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.ai.AiClient;
import com.resume.screening.ai.AiException;
import com.resume.screening.cache.RedisKeys;
import com.resume.screening.cache.RedisLock;
import com.resume.screening.cache.ResumeStatusCache;
import com.resume.screening.common.Json;
import com.resume.screening.config.AppProperties;
import com.resume.screening.domain.ApplicationStatus;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.domain.Resume;
import com.resume.screening.domain.ResumeStatus;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.ResumeMapper;
import com.resume.screening.service.StorageService;
import jakarta.annotation.PreDestroy;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.client.consumer.DefaultMQPushConsumer;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyContext;
import org.apache.rocketmq.client.consumer.listener.ConsumeConcurrentlyStatus;
import org.apache.rocketmq.client.consumer.listener.MessageListenerConcurrently;
import org.apache.rocketmq.common.message.MessageExt;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * resume-extract 消费者：调 Python 抽取 Profile 并回写。
 *
 * 三件必须做对的事：
 * 1) 幂等 —— Redis SETNX 锁 + "已 EXTRACTED 就跳过" 双重兜底；
 * 2) 有界重试 —— 最多 maxReconsumeTimes 次，最后一次仍失败则落 FAILED，绝不静默丢弃；
 * 3) 不浪费调用 —— 无文本层 / Schema 不合规这类重试无意义的错误，直接 FAILED。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExtractConsumer implements InitializingBean {

    private final AppProperties props;
    private final Json json;
    private final ResumeMapper resumeMapper;
    private final JobApplicationMapper applicationMapper;
    private final MqProducer producer;
    private final AiClient aiClient;
    private final StorageService storage;
    private final ResumeStatusCache statusCache;
    private final RedisLock lock;

    private DefaultMQPushConsumer consumer;

    @Override
    public void afterPropertiesSet() {
        if (!props.getMq().isEnabled()) {
            return;
        }
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.getMq().getExtractConsumerGroup());
        c.setNamesrvAddr(props.getMq().getNamesrvAddr());
        c.setConsumeThreadMin(props.getMq().getConsumeThreads());
        c.setConsumeThreadMax(props.getMq().getConsumeThreads());
        c.setConsumeMessageBatchMaxSize(1);
        c.setPullBatchSize(1);
        c.setMaxReconsumeTimes(props.getMq().getMaxReconsumeTimes());
        try {
            c.subscribe(props.getMq().getExtractTopic(), "*");
            c.registerMessageListener((MessageListenerConcurrently) this::onMessages);
            c.start();
            this.consumer = c;
            log.info("ExtractConsumer 启动成功 group={} topic={} threads={} maxReconsume={}",
                    props.getMq().getExtractConsumerGroup(), props.getMq().getExtractTopic(),
                    props.getMq().getConsumeThreads(), props.getMq().getMaxReconsumeTimes());
        } catch (Exception e) {
            log.error("ExtractConsumer 启动失败：{}", e.getMessage());
            try {
                c.shutdown();
            } catch (Exception ignore) {
                // 启动失败，关闭异常无需关注
            }
        }
    }

    private ConsumeConcurrentlyStatus onMessages(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : messages) {
            ExtractMessage body;
            try {
                body = json.read(new String(msg.getBody(), StandardCharsets.UTF_8), ExtractMessage.class);
            } catch (Exception e) {
                log.error("消息体解析失败，丢弃 msgId={}", msg.getMsgId(), e);
                continue;
            }
            if (body == null || body.resumeId() == null) {
                log.error("消息体缺少 resumeId，丢弃 msgId={}", msg.getMsgId());
                continue;
            }
            if (handle(msg, body) == ConsumeConcurrentlyStatus.RECONSUME_LATER) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private ConsumeConcurrentlyStatus handle(MessageExt msg, ExtractMessage body) {
        long resumeId = body.resumeId();
        int reconsume = msg.getReconsumeTimes();
        int attempt = reconsume + 1;
        String lockKey = RedisKeys.resumeLock(resumeId);

        String token = lock.tryLock(lockKey, Duration.ofSeconds(120));
        if (token == null) {
            log.info("幂等锁拦截，跳过 resumeId={} msgId={} reconsume={}", resumeId, msg.getMsgId(), reconsume);
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        try {
            Resume resume = resumeMapper.selectById(resumeId);
            if (resume == null) {
                log.warn("简历不存在，丢弃消息 resumeId={} msgId={}", resumeId, msg.getMsgId());
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            if (ResumeStatus.EXTRACTED.name().equals(resume.getStatus())) {
                log.info("简历已解析完成，跳过重复消费 resumeId={} msgId={}", resumeId, msg.getMsgId());
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }

            resumeMapper.updateStatus(resumeId, ResumeStatus.PARSING.name(), null);
            statusCache.put(resumeId, ResumeStatus.PARSING.name(), null, resume.getCandidateName());

            long t0 = System.currentTimeMillis();
            try {
                byte[] pdf = storage.read(resume.getFilePath());
                AiClient.ExtractOutcome outcome = aiClient.extract(pdf, resume.getFileName());

                JsonNode profile = json.read(outcome.profileJson());
                String name = textOf(profile, "name");
                String phone = textOf(profile, "phone");
                String email = textOf(profile, "email");
                int costMs = (int) (System.currentTimeMillis() - t0);

                resumeMapper.markExtracted(resumeId, outcome.profileJson(), outcome.rawText(),
                        costMs, name, phone, email);

                statusCache.put(resumeId, ResumeStatus.EXTRACTED.name(), null, name);
                statusCache.putProfile(resumeId, outcome.profileJson());

                // 官网投递的简历先落 WAITING，解析成功后在这里接力把打分任务投出去
                int promoted = promoteWaitingApplications(resumeId);

                log.info("简历抽取完成 resumeId={} msgId={} attempt={} costMs={} tokens={} name={} 触发打分={}",
                        resumeId, msg.getMsgId(), attempt, costMs,
                        outcome.promptTokens() + outcome.completionTokens(), name, promoted);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;

            } catch (AiException e) {
                boolean last = reconsume >= props.getMq().getMaxReconsumeTimes();
                if (!e.isRetryable() || last) {
                    String err = truncate(e.getMessage(), 480);
                    markFailed(resumeId, err);
                    log.error("简历抽取失败（终止）resumeId={} msgId={} attempt={} code={} err={}",
                            resumeId, msg.getMsgId(), attempt, e.getCode(), err);
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
                log.warn("简历抽取失败，等待重试 resumeId={} msgId={} attempt={} err={}",
                        resumeId, msg.getMsgId(), attempt, e.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            } catch (Exception e) {
                boolean last = reconsume >= props.getMq().getMaxReconsumeTimes();
                if (last) {
                    markFailed(resumeId, truncate("处理异常：" + e.getMessage(), 480));
                    log.error("简历抽取异常且重试耗尽 resumeId={} msgId={}", resumeId, msg.getMsgId(), e);
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
                log.warn("简历抽取异常，等待重试 resumeId={} msgId={} attempt={}",
                        resumeId, msg.getMsgId(), attempt, e);
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        } finally {
            lock.unlock(lockKey, token);
        }
    }

    /**
     * 简历解析成功后，把等着这份简历的投递记录推进到打分队列。
     * claimForScoring 带 status='WAITING' 条件，并发消费时只有一个线程能抢到，不会重复投递。
     */
    private int promoteWaitingApplications(long resumeId) {
        List<JobApplication> waiting = applicationMapper.selectWaitingByResumeId(resumeId);
        int promoted = 0;
        for (JobApplication app : waiting) {
            if (applicationMapper.claimForScoring(app.getId()) != 1) {
                continue;
            }
            try {
                producer.send(props.getMq().getMatchTopic(), String.valueOf(app.getId()),
                        new MatchMessage(UUID.randomUUID().toString(), app.getId(), resumeId,
                                app.getJobId(), System.currentTimeMillis()));
                promoted++;
            } catch (Exception e) {
                // 发不出去就退回 WAITING，交给对账任务重来，别让记录卡在 SCORING
                applicationMapper.updateStatus(app.getId(), ApplicationStatus.WAITING.name(), null);
                log.error("触发打分消息失败 applicationId={} err={}", app.getId(), e.getMessage());
            }
        }
        return promoted;
    }

    /** 解析彻底失败：简历判 FAILED，等它的投递记录也一并判失败 */
    private void markFailed(long resumeId, String err) {
        resumeMapper.updateStatus(resumeId, ResumeStatus.FAILED.name(), err);
        statusCache.put(resumeId, ResumeStatus.FAILED.name(), err, null);
        int affected = applicationMapper.failWaitingByResumeId(resumeId, truncate(err, 480));
        if (affected > 0) {
            log.warn("简历解析失败，连带作废 {} 条等待中的投递 resumeId={}", affected, resumeId);
        }
    }

    private static String textOf(JsonNode node, String field) {
        if (node == null) {
            return null;
        }
        JsonNode v = node.path(field);
        return v.isMissingNode() || v.isNull() ? null : v.asText();
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return null;
        }
        return s.length() <= max ? s : s.substring(0, max);
    }

    @PreDestroy
    public void shutdown() {
        if (consumer != null) {
            consumer.shutdown();
            log.info("ExtractConsumer 已关闭");
        }
    }
}
