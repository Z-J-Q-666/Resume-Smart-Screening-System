package com.resume.screening.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.common.Json;
import com.resume.screening.domain.Job;
import com.resume.screening.dto.JobRequest;
import com.resume.screening.dto.Views;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

@Slf4j
@Service
@RequiredArgsConstructor
public class JobService {

    private static final Set<String> EDUCATION_LEVELS = Set.of("不限", "大专", "本科", "硕士", "博士");

    private final JobMapper jobMapper;
    private final JobApplicationMapper applicationMapper;
    private final Json json;

    @Transactional
    public Long create(JobRequest req) {
        Job job = new Job();
        apply(job, req);
        job.setStatus(1);
        jobMapper.insert(job);
        log.info("创建岗位 id={} title={} mustSkills={}", job.getId(), job.getTitle(),
                json.toStringList(job.getMustSkills()));
        return job.getId();
    }

    @Transactional
    public void update(long id, JobRequest req) {
        Job job = require(id);
        apply(job, req);
        jobMapper.updateById(job);
    }

    @Transactional
    public void updateStatus(long id, int status) {
        if (status != 0 && status != 1) {
            throw new BizException(ErrorCode.BAD_REQUEST, "岗位状态只能是 0（关闭）或 1（开放）");
        }
        Job job = require(id);
        Job update = new Job();
        update.setId(job.getId());
        update.setStatus(status);
        jobMapper.updateById(update);
        log.info("岗位状态变更 id={} status={}", id, status);
    }

    public Views.PageView<Views.JobView> list(long page, long size, Integer status) {
        LambdaQueryWrapper<Job> qw = new LambdaQueryWrapper<Job>()
                .eq(status != null, Job::getStatus, status)
                .orderByDesc(Job::getId);
        Page<Job> p = jobMapper.selectPage(new Page<>(page, Math.min(size, 100)), qw);

        // 一次 GROUP BY 把所有岗位的投递数与均分取回来，避免逐行查询
        Map<Long, com.resume.screening.dto.JobStat> statMap = applicationMapper.selectJobStats().stream()
                .collect(java.util.stream.Collectors.toMap(
                        com.resume.screening.dto.JobStat::getJobId, java.util.function.Function.identity(),
                        (a, b) -> a));

        List<Views.JobView> records = new ArrayList<>(p.getRecords().size());
        for (Job j : p.getRecords()) {
            var stat = statMap.get(j.getId());
            records.add(toView(j,
                    stat == null ? 0L : stat.getApplicationCount(),
                    stat == null || stat.getAvgScore() == null
                            ? null : stat.getAvgScore().setScale(2, RoundingMode.HALF_UP)));
        }
        return new Views.PageView<>(p.getTotal(), p.getCurrent(), p.getSize(), records);
    }

    public Views.JobView detail(long id) {
        Job job = require(id);
        long count = applicationMapper.countByJobId(id);
        BigDecimal avg = applicationMapper.avgScoreByJobId(id);
        return toView(job, count, avg == null ? null : avg.setScale(2, RoundingMode.HALF_UP));
    }

    public Job require(long id) {
        Job job = jobMapper.selectById(id);
        if (job == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "岗位不存在：" + id);
        }
        return job;
    }

    // ---------------------------------------------------------------- internal

    private void apply(Job job, JobRequest req) {
        String edu = req.educationReq() == null || req.educationReq().isBlank() ? "不限" : req.educationReq().trim();
        if (!EDUCATION_LEVELS.contains(edu)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "学历要求只能是：不限/大专/本科/硕士/博士");
        }
        List<String> must = normalizeSkills(req.mustSkills());
        if (must.isEmpty()) {
            throw new BizException(ErrorCode.MUST_SKILL_EMPTY);
        }
        if (must.size() > 20) {
            throw new BizException(ErrorCode.BAD_REQUEST, "必备技能最多 20 项");
        }
        int years = req.expYearsReq() == null ? 0 : req.expYearsReq();
        if (years < 0 || years > 30) {
            throw new BizException(ErrorCode.BAD_REQUEST, "年限要求需在 0-30 之间");
        }

        job.setTitle(req.title().trim());
        // 用空串而非 null：MyBatis-Plus updateById 默认跳过 null 字段，
        // 否则把 JD 或部门清空时旧值会残留
        job.setDepartment(req.department() == null ? "" : req.department().trim());
        job.setEducationReq(edu);
        job.setExpYearsReq(years);
        job.setMustSkills(json.write(must));
        job.setPlusSkills(json.write(normalizeSkills(req.plusSkills())));
        job.setJdText(req.jdText() == null ? "" : req.jdText());
    }

    /** trim + 大小写不敏感去重，保持录入顺序（需求 FR-JOB-04） */
    private List<String> normalizeSkills(List<String> input) {
        if (input == null) {
            return List.of();
        }
        Map<String, String> unique = new LinkedHashMap<>();
        for (String s : input) {
            if (s == null) {
                continue;
            }
            String v = s.trim();
            if (v.isEmpty()) {
                continue;
            }
            unique.putIfAbsent(v.toLowerCase(Locale.ROOT), v);
        }
        return new ArrayList<>(unique.values());
    }

    private Views.JobView toView(Job j, Long count, BigDecimal avg) {
        return new Views.JobView(j.getId(), j.getTitle(), j.getDepartment(), j.getEducationReq(),
                j.getExpYearsReq(), json.toStringList(j.getMustSkills()), json.toStringList(j.getPlusSkills()),
                j.getJdText(), j.getStatus(), j.getCreatedAt(), j.getUpdatedAt(), count, avg);
    }
}
