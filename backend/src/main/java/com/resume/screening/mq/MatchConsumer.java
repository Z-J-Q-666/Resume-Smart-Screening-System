package com.resume.screening.mq;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.ai.AiClient;
import com.resume.screening.ai.AiException;
import com.resume.screening.cache.BoardCache;
import com.resume.screening.cache.RedisKeys;
import com.resume.screening.cache.RedisLock;
import com.resume.screening.common.Json;
import com.resume.screening.config.AppProperties;
import com.resume.screening.domain.ApplicationStatus;
import com.resume.screening.domain.Job;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.domain.Resume;
import com.resume.screening.domain.ResumeStatus;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.JobMapper;
import com.resume.screening.mapper.ResumeMapper;
import com.resume.screening.match.ScoreCalculator;
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

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;

/**
 * match-score 消费者：规则分（Java 算）+ 语义分（LLM 给）+ 综合分。
 * 打分失败只影响这条投递记录，简历本身仍然可用（需求 FR-MAT-06）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class MatchConsumer implements InitializingBean {

    private final AppProperties props;
    private final Json json;
    private final JobMapper jobMapper;
    private final ResumeMapper resumeMapper;
    private final JobApplicationMapper applicationMapper;
    private final ScoreCalculator scoreCalculator;
    private final AiClient aiClient;
    private final BoardCache boardCache;
    private final RedisLock lock;

    private DefaultMQPushConsumer consumer;

    @Override
    public void afterPropertiesSet() {
        if (!props.getMq().isEnabled()) {
            return;
        }
        DefaultMQPushConsumer c = new DefaultMQPushConsumer(props.getMq().getMatchConsumerGroup());
        c.setNamesrvAddr(props.getMq().getNamesrvAddr());
        c.setConsumeThreadMin(props.getMq().getConsumeThreads());
        c.setConsumeThreadMax(props.getMq().getConsumeThreads());
        c.setConsumeMessageBatchMaxSize(1);
        c.setPullBatchSize(1);
        c.setMaxReconsumeTimes(props.getMq().getMaxReconsumeTimes());
        try {
            c.subscribe(props.getMq().getMatchTopic(), "*");
            c.registerMessageListener((MessageListenerConcurrently) this::onMessages);
            c.start();
            this.consumer = c;
            log.info("MatchConsumer 启动成功 group={} topic={} threads={}",
                    props.getMq().getMatchConsumerGroup(), props.getMq().getMatchTopic(),
                    props.getMq().getConsumeThreads());
        } catch (Exception e) {
            log.error("MatchConsumer 启动失败：{}", e.getMessage());
            try {
                c.shutdown();
            } catch (Exception ignore) {
                // 启动失败，关闭异常无需关注
            }
        }
    }

    private ConsumeConcurrentlyStatus onMessages(List<MessageExt> messages, ConsumeConcurrentlyContext context) {
        for (MessageExt msg : messages) {
            MatchMessage body;
            try {
                body = json.read(new String(msg.getBody(), StandardCharsets.UTF_8), MatchMessage.class);
            } catch (Exception e) {
                log.error("消息体解析失败，丢弃 msgId={}", msg.getMsgId(), e);
                continue;
            }
            if (body == null || body.applicationId() == null) {
                log.error("消息体缺少 applicationId，丢弃 msgId={}", msg.getMsgId());
                continue;
            }
            if (handle(msg, body) == ConsumeConcurrentlyStatus.RECONSUME_LATER) {
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }
        }
        return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
    }

    private ConsumeConcurrentlyStatus handle(MessageExt msg, MatchMessage body) {
        long applicationId = body.applicationId();
        int reconsume = msg.getReconsumeTimes();
        int attempt = reconsume + 1;
        String lockKey = RedisKeys.matchLock(applicationId);

        String token = lock.tryLock(lockKey, Duration.ofSeconds(120));
        if (token == null) {
            log.info("幂等锁拦截，跳过 applicationId={} msgId={}", applicationId, msg.getMsgId());
            return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
        }

        try {
            JobApplication app = applicationMapper.selectById(applicationId);
            if (app == null) {
                log.warn("投递记录不存在，丢弃消息 applicationId={}", applicationId);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            if (ApplicationStatus.SCORED.name().equals(app.getStatus())) {
                log.info("投递已打分，跳过重复消费 applicationId={}", applicationId);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }

            Job job = jobMapper.selectById(app.getJobId());
            Resume resume = resumeMapper.selectById(app.getResumeId());
            if (job == null || resume == null) {
                String err = job == null ? "岗位已不存在" : "简历已不存在";
                fail(applicationId, err);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            if (!ResumeStatus.EXTRACTED.name().equals(resume.getStatus())) {
                fail(applicationId, "简历未解析完成，无法打分");
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }

            JsonNode profile = json.read(resume.getParsedJson());
            if (profile == null) {
                fail(applicationId, "简历 Profile 缺失，请重新解析");
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }

            long t0 = System.currentTimeMillis();
            ScoreCalculator.Breakdown breakdown = scoreCalculator.calculate(job, profile);
            List<String> plusSkills = json.toStringList(job.getPlusSkills());

            try {
                AiClient.MatchOutcome semantic = aiClient.match(
                        job.getTitle(), job.getJdText(),
                        json.toStringList(job.getMustSkills()), plusSkills, profile);

                BigDecimal ruleScore = BigDecimal.valueOf(breakdown.ruleScore());
                BigDecimal semanticScore = BigDecimal.valueOf(semantic.semanticScore());
                BigDecimal total = ruleScore.multiply(BigDecimal.valueOf(props.getScore().getRuleWeight()))
                        .add(semanticScore.multiply(BigDecimal.valueOf(props.getScore().getSemanticWeight())))
                        .setScale(2, RoundingMode.HALF_UP);

                applicationMapper.markScored(applicationId,
                        ruleScore, semanticScore, total,
                        json.write(breakdown.hitSkills()),
                        json.write(breakdown.missSkills()),
                        semantic.reason());

                boardCache.update(job.getId(), resume.getId(), total.doubleValue());

                log.info("打分完成 applicationId={} jobId={} resumeId={} edu={} exp={} skill={} "
                                + "rule={} semantic={} total={} costMs={}",
                        applicationId, job.getId(), resume.getId(),
                        breakdown.educationScore(), breakdown.expScore(), breakdown.skillScore(),
                        breakdown.ruleScore(), semantic.semanticScore(), total,
                        System.currentTimeMillis() - t0);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;

            } catch (AiException e) {
                boolean last = reconsume >= props.getMq().getMaxReconsumeTimes();
                if (!e.isRetryable() || last) {
                    fail(applicationId, truncate("语义打分失败：" + e.getMessage(), 480));
                    log.error("打分失败（终止）applicationId={} attempt={} code={} err={}",
                            applicationId, attempt, e.getCode(), e.getMessage());
                    return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
                }
                log.warn("打分失败，等待重试 applicationId={} attempt={} err={}",
                        applicationId, attempt, e.getMessage());
                return ConsumeConcurrentlyStatus.RECONSUME_LATER;
            }

        } catch (Exception e) {
            boolean last = reconsume >= props.getMq().getMaxReconsumeTimes();
            if (last) {
                fail(applicationId, truncate("打分异常：" + e.getMessage(), 480));
                log.error("打分异常且重试耗尽 applicationId={}", applicationId, e);
                return ConsumeConcurrentlyStatus.CONSUME_SUCCESS;
            }
            log.warn("打分异常，等待重试 applicationId={} attempt={}", applicationId, attempt, e);
            return ConsumeConcurrentlyStatus.RECONSUME_LATER;
        } finally {
            lock.unlock(lockKey, token);
        }
    }

    private void fail(long applicationId, String errorMsg) {
        applicationMapper.updateStatus(applicationId, ApplicationStatus.FAILED.name(), errorMsg);
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
            log.info("MatchConsumer 已关闭");
        }
    }
}
