package com.resume.screening.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.metadata.IPage;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.resume.screening.cache.BoardCache;
import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.common.Json;
import com.resume.screening.config.AppProperties;
import com.resume.screening.domain.ApplicationStatus;
import com.resume.screening.domain.Job;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.domain.Resume;
import com.resume.screening.domain.ResumeStatus;
import com.resume.screening.dto.BoardRow;
import com.resume.screening.dto.Views;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.ResumeMapper;
import com.resume.screening.match.ScoreCalculator;
import com.resume.screening.mq.MatchMessage;
import com.resume.screening.mq.MqProducer;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.ZSetOperations;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

@Slf4j
@Service
@RequiredArgsConstructor
public class ApplicationService {

    private final JobService jobService;
    private final ResumeMapper resumeMapper;
    private final JobApplicationMapper applicationMapper;
    private final Json json;
    private final MqProducer producer;
    private final AppProperties props;
    private final ScoreCalculator scoreCalculator;
    private final BoardCache boardCache;
    private final TransactionTemplate txTemplate;

    // ------------------------------------------------------------------ 投递

    public Views.ApplyResult apply(long jobId, long resumeId) {
        Job job = jobService.require(jobId);
        if (job.getStatus() != null && job.getStatus() == 0) {
            throw new BizException(ErrorCode.JOB_CLOSED, "岗位已关闭，不接受新投递");
        }

        Resume resume = resumeMapper.selectById(resumeId);
        if (resume == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "简历不存在：" + resumeId);
        }
        if (!ResumeStatus.EXTRACTED.name().equals(resume.getStatus())) {
            throw new BizException(ErrorCode.RESUME_NOT_READY,
                    "简历尚未解析完成（当前状态 " + resume.getStatus() + "），请等待解析后再投递");
        }

        Long applicationId = txTemplate.execute(status -> {
            JobApplication existing = applicationMapper.selectOne(new LambdaQueryWrapper<JobApplication>()
                    .eq(JobApplication::getJobId, jobId)
                    .eq(JobApplication::getResumeId, resumeId));

            Long id;
            if (existing != null) {
                // 重复投递即更新：覆盖原分数，不新增记录（需求 FR-MAT-05）
                applicationMapper.resetForScoring(existing.getId());
                boardCache.remove(jobId, resumeId);
                id = existing.getId();
                log.info("重复投递，重置打分 applicationId={} jobId={} resumeId={}", id, jobId, resumeId);
            } else {
                JobApplication app = new JobApplication();
                app.setJobId(jobId);
                app.setResumeId(resumeId);
                app.setStatus(ApplicationStatus.SCORING.name());
                try {
                    applicationMapper.insert(app);
                    id = app.getId();
                } catch (DuplicateKeyException e) {
                    JobApplication dup = applicationMapper.selectOne(new LambdaQueryWrapper<JobApplication>()
                            .eq(JobApplication::getJobId, jobId)
                            .eq(JobApplication::getResumeId, resumeId));
                    if (dup == null) {
                        throw new BizException(ErrorCode.INTERNAL, "投递冲突，请重试");
                    }
                    applicationMapper.resetForScoring(dup.getId());
                    boardCache.remove(jobId, resumeId);
                    id = dup.getId();
                }
            }

            producer.sendAfterCommit(props.getMq().getMatchTopic(), String.valueOf(id),
                    new MatchMessage(UUID.randomUUID().toString(), id, resumeId, jobId,
                            System.currentTimeMillis()));
            return id;
        });

        log.info("投递已受理 applicationId={} jobId={} resumeId={}", applicationId, jobId, resumeId);
        return new Views.ApplyResult(applicationId, ApplicationStatus.SCORING.name());
    }

    // ------------------------------------------------------------------ 详情

    public Views.ApplicationDetailView detail(long applicationId) {
        JobApplication app = applicationMapper.selectById(applicationId);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投递记录不存在：" + applicationId);
        }
        Job job = jobService.require(app.getJobId());
        Resume resume = resumeMapper.selectById(app.getResumeId());
        var profile = resume == null ? null : json.read(resume.getParsedJson());

        // 分项分不落库：规则分可复现，读时按当前岗位要求重算，永远自洽（需求 FR-MAT-02）
        ScoreCalculator.Breakdown breakdown = scoreCalculator.calculate(job, profile);
        if (app.getRuleScore() != null
                && Math.abs(app.getRuleScore().doubleValue() - breakdown.ruleScore()) > 0.01) {
            log.warn("规则分与存储值不一致（岗位要求可能已变更）applicationId={} stored={} recomputed={}",
                    applicationId, app.getRuleScore(), breakdown.ruleScore());
        }

        Views.BreakdownView bd = new Views.BreakdownView(
                breakdown.educationScore(), breakdown.expScore(), breakdown.skillScore(),
                breakdown.ruleScore(), app.getSemanticScore(),
                new Views.Weights(props.getScore().getRuleWeight(), props.getScore().getSemanticWeight()));

        return new Views.ApplicationDetailView(
                app.getId(), job.getId(), job.getTitle(), app.getResumeId(),
                resume == null ? null : resume.getCandidateName(),
                app.getStatus(), app.getScore(), app.getErrorMsg(), bd,
                breakdown.hitSkills(), breakdown.missSkills(),
                app.getReason(), profile, app.getCreatedAt());
    }

    // ------------------------------------------------------------------ 看板

    public Views.PageView<Views.BoardItem> board(long jobId, long page, long size,
                                                 BigDecimal minScore, String degree) {
        Job job = jobService.require(jobId);
        int mustCount = json.toStringList(job.getMustSkills()).size();

        IPage<BoardRow> p = applicationMapper.selectBoard(
                new Page<>(page, Math.min(size, 100)), jobId, minScore, degree);

        List<Views.BoardItem> records = p.getRecords().stream()
                .map(r -> {
                    List<String> hit = json.toStringList(r.getHitSkills());
                    List<String> miss = json.toStringList(r.getMissSkills());
                    return new Views.BoardItem(r.getId(), r.getResumeId(), r.getCandidateName(),
                            r.getPhone(), r.getEmail(),
                            r.getDegree(), r.getTotalYears(), r.getScore(), r.getRuleScore(),
                            r.getSemanticScore(), hit.size(), mustCount, r.getStatus(),
                            r.getReason(), hit, miss, r.getErrorMsg(), r.getCreatedAt());
                })
                .toList();

        return new Views.PageView<>(p.getTotal(), p.getCurrent(), p.getSize(), records);
    }

    /**
     * TOP N 快速通道：Redis ZSet 命中就直接返回，缺失则从 MySQL 重建一次（需求 §8 原则 3）。
     */
    public List<Views.JobTopItem> top(long jobId, int n) {
        jobService.require(jobId);
        int limit = Math.max(1, Math.min(n, 50));

        List<Long> ids = boardCache.top(jobId, limit);
        String source = "redis";
        if (ids.isEmpty()) {
            rebuildBoard(jobId);
            ids = boardCache.top(jobId, limit);
            source = "mysql";
        }
        if (ids.isEmpty()) {
            return List.of();
        }

        Map<Long, BoardRow> rows = applicationMapper.selectTopRows(jobId, ids).stream()
                .collect(Collectors.toMap(BoardRow::getResumeId, Function.identity(), (a, b) -> a));

        List<Views.JobTopItem> result = new ArrayList<>(ids.size());
        for (Long rid : ids) {
            BoardRow row = rows.get(rid);
            if (row == null) {
                continue;
            }
            result.add(new Views.JobTopItem(rid, row.getCandidateName(), row.getDegree(),
                    row.getTotalYears(), row.getScore(), source));
        }
        return result;
    }

    private void rebuildBoard(long jobId) {
        var scored = applicationMapper.selectScoredByJob(jobId);
        List<ZSetOperations.TypedTuple<String>> tuples = new ArrayList<>(scored.size());
        for (JobApplication app : scored) {
            if (app.getScore() == null) {
                continue;
            }
            tuples.add(ZSetOperations.TypedTuple.of(String.valueOf(app.getResumeId()),
                    app.getScore().doubleValue()));
        }
        boardCache.replaceAll(jobId, new ArrayList<>(new LinkedHashSet<>(tuples)));
        log.info("排行榜已从 MySQL 重建 jobId={} size={}", jobId, tuples.size());
    }
}
