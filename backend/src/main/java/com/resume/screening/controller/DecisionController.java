package com.resume.screening.controller;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.ai.AiClient;
import com.resume.screening.common.ApiResponse;
import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.common.Json;
import com.resume.screening.domain.ApplicationStatus;
import com.resume.screening.domain.HiringDecision;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.domain.Resume;
import com.resume.screening.dto.DecisionRequest;
import com.resume.screening.mapper.HiringDecisionMapper;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.ResumeMapper;
import com.resume.screening.service.CalibrationService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 录用决策与校准预览。
 *
 * <p>决策是"人工反馈"的唯一入口——没有它，语义分永远只能自我循环，
 * 无从判断模型打的分到底偏高还是偏低。
 */
@Slf4j
@RestController
@RequestMapping("/api")
@RequiredArgsConstructor
public class DecisionController {

    private final HiringDecisionMapper decisionMapper;
    private final JobApplicationMapper applicationMapper;
    private final ResumeMapper resumeMapper;
    private final CalibrationService calibrationService;
    private final Json json;

    /** 标记录用/淘汰。同一条投递重复提交即覆盖，不新增记录。 */
    @PostMapping("/applications/{id}/decision")
    public ApiResponse<Void> decide(@PathVariable long id, @RequestBody DecisionRequest req) {
        JobApplication app = applicationMapper.selectById(id);
        if (app == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "投递记录不存在：" + id);
        }
        if (!ApplicationStatus.SCORED.name().equals(app.getStatus())) {
            throw new BizException(ErrorCode.BAD_REQUEST, "只有已打分的投递才能标记决策");
        }

        String outcome = req.outcome() == null ? "" : req.outcome().trim().toUpperCase();
        if (!HiringDecision.HIRED.equals(outcome) && !HiringDecision.REJECTED.equals(outcome)) {
            throw new BizException(ErrorCode.BAD_REQUEST, "outcome 只能是 HIRED 或 REJECTED");
        }

        HiringDecision existing = decisionMapper.selectOne(new LambdaQueryWrapper<HiringDecision>()
                .eq(HiringDecision::getApplicationId, id));

        HiringDecision d = new HiringDecision();
        d.setApplicationId(id);
        d.setJobId(app.getJobId());
        d.setResumeId(app.getResumeId());
        d.setOutcome(outcome);
        d.setNote(req.note());
        d.setDecidedBy("hr");
        d.setDecidedAt(LocalDateTime.now());

        if (existing == null) {
            decisionMapper.insert(d);
        } else {
            d.setId(existing.getId());
            decisionMapper.updateById(d);
        }
        log.info("录用决策 applicationId={} jobId={} resumeId={} outcome={} 改判={}",
                id, app.getJobId(), app.getResumeId(), outcome, existing != null);
        return ApiResponse.ok();
    }

    /**
     * 预览：这个岗位、这个候选人，打分时会注入哪几个参照。
     * 调试和演示用——否则"RAG 校准"在界面上是不可见的。
     */
    @GetMapping("/jobs/{jobId}/calibration")
    public ApiResponse<List<AiClient.Example>> calibration(@PathVariable long jobId,
                                                           @RequestParam long resumeId) {
        Resume r = resumeMapper.selectById(resumeId);
        if (r == null) {
            throw new BizException(ErrorCode.NOT_FOUND, "简历不存在：" + resumeId);
        }
        JsonNode profile = json.read(r.getParsedJson());
        return ApiResponse.ok(calibrationService.pickExamples(jobId, resumeId, profile));
    }
}
