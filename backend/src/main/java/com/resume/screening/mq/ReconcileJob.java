package com.resume.screening.mq;

import com.resume.screening.config.AppProperties;
import com.resume.screening.domain.ApplicationStatus;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.domain.Resume;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.UUID;

/**
 * 对账任务：兜住"任务丢了"的情况，这正是 MQ 相对 @Async 的价值所在（需求 §1.2 目标 3）。
 *
 * 场景一：上传后 MQ 不可用，消息没投出去 → 简历停在 PENDING。
 * 场景二：消费者处理到一半进程被杀 → 简历停在 PARSING。
 * 两者都由这里重新投递，消费者靠幂等锁 + 状态判断保证不重复调 LLM。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ReconcileJob {

    private static final int PENDING_STALE_SECONDS = 60;
    private static final int PARSING_STALE_SECONDS = 300;
    private static final int SCORING_STALE_SECONDS = 300;
    private static final int BATCH = 50;

    private final ResumeMapper resumeMapper;
    private final JobApplicationMapper applicationMapper;
    private final MqProducer producer;
    private final AppProperties props;

    @Scheduled(initialDelay = 45_000, fixedDelay = 60_000)
    public void run() {
        if (!producer.isAvailable()) {
            return;
        }
        try {
            resendResumes();
            promoteOrphanWaiting();
            resendApplications();
        } catch (Exception e) {
            log.error("对账任务执行异常", e);
        }
    }

    /** 简历已解析但投递还停在 WAITING：补一次打分投递 */
    private void promoteOrphanWaiting() {
        List<JobApplication> orphans = applicationMapper.selectWaitingWithParsedResume(BATCH);
        for (JobApplication a : orphans) {
            if (applicationMapper.claimForScoring(a.getId()) != 1) {
                continue;
            }
            try {
                producer.send(props.getMq().getMatchTopic(), String.valueOf(a.getId()),
                        new MatchMessage(UUID.randomUUID().toString(), a.getId(), a.getResumeId(), a.getJobId(),
                                System.currentTimeMillis()));
                log.warn("对账补投打分 applicationId={}", a.getId());
            } catch (Exception e) {
                applicationMapper.updateStatus(a.getId(), ApplicationStatus.WAITING.name(), null);
                log.error("对账补投打分失败 applicationId={} err={}", a.getId(), e.getMessage());
            }
        }
    }

    private void resendResumes() {
        List<Resume> stale = resumeMapper.selectStale(PENDING_STALE_SECONDS, PARSING_STALE_SECONDS, BATCH);
        for (Resume r : stale) {
            try {
                producer.send(props.getMq().getExtractTopic(), String.valueOf(r.getId()),
                        new ExtractMessage(UUID.randomUUID().toString(), r.getId(), r.getFilePath(), 1,
                                System.currentTimeMillis()));
                log.warn("对账重投简历解析 resumeId={} status={}", r.getId(), r.getStatus());
            } catch (Exception e) {
                log.error("对账重投失败 resumeId={} err={}", r.getId(), e.getMessage());
            }
        }
    }

    private void resendApplications() {
        List<JobApplication> stale = applicationMapper.selectStaleScoring(SCORING_STALE_SECONDS, BATCH);
        for (JobApplication a : stale) {
            try {
                producer.send(props.getMq().getMatchTopic(), String.valueOf(a.getId()),
                        new MatchMessage(UUID.randomUUID().toString(), a.getId(), a.getResumeId(), a.getJobId(),
                                System.currentTimeMillis()));
                log.warn("对账重投打分 applicationId={}", a.getId());
            } catch (Exception e) {
                log.error("对账重投打分失败 applicationId={} err={}", a.getId(), e.getMessage());
            }
        }
    }
}
