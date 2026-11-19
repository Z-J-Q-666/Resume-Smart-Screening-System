package com.resume.screening.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.resume.screening.agent.AgentTool;
import com.resume.screening.common.Json;
import com.resume.screening.domain.HiringDecision;
import com.resume.screening.domain.JobApplication;
import com.resume.screening.domain.Resume;
import com.resume.screening.mapper.HiringDecisionMapper;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 看某一条投递的分数拆解。回答"这个人为什么是这个分"。 */
@Component
@RequiredArgsConstructor
public class ApplicationDetailTool implements AgentTool {

    private final JobApplicationMapper applicationMapper;
    private final ResumeMapper resumeMapper;
    private final HiringDecisionMapper decisionMapper;
    private final Json json;

    @Override
    public String name() {
        return "get_application_detail";
    }

    @Override
    public String description() {
        return "查某条投递的详细打分：规则分、语义分、综合分、命中/缺失技能、推荐理由。"
                + "需要解释『为什么这个人分低』『他缺什么技能』时使用。applicationId 从 search_applications 的结果里拿。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("applicationId", Map.of("type", "integer", "description", "投递记录 ID"));
        return Map.of("type", "object", "properties", props, "required", List.of("applicationId"));
    }

    @Override
    public String execute(JsonNode args) {
        long id = args.path("applicationId").asLong(0);
        if (id <= 0) {
            return json.write(Map.of("error", "缺少 applicationId"));
        }

        JobApplication app = applicationMapper.selectById(id);
        if (app == null) {
            return json.write(Map.of("error", "投递记录不存在：" + id));
        }
        Resume resume = resumeMapper.selectById(app.getResumeId());

        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", id);
        m.put("jobId", app.getJobId());
        m.put("candidateName", resume == null ? null : resume.getCandidateName());
        m.put("degree", resume == null ? null : degreeOf(resume));
        m.put("totalYears", resume == null ? null : yearsOf(resume));
        m.put("status", app.getStatus());
        m.put("ruleScore", app.getRuleScore());
        m.put("semanticScore", app.getSemanticScore());
        m.put("score", app.getScore());
        m.put("hitSkills", json.toStringList(app.getHitSkills()));
        m.put("missSkills", json.toStringList(app.getMissSkills()));
        m.put("reason", app.getReason());
        m.put("errorMsg", app.getErrorMsg());

        // 顺带告诉模型这个人有没有被标记过录用，避免它重复建议"去标记一下"
        HiringDecision decision = decisionMapper.selectOne(new LambdaQueryWrapper<HiringDecision>()
                .eq(HiringDecision::getApplicationId, id));
        m.put("decision", decision == null ? null : decision.getOutcome());

        return json.write(m);
    }

    private String degreeOf(Resume r) {
        JsonNode p = json.read(r.getParsedJson());
        String d = p == null ? "" : p.path("education").path("degree").asText("");
        return d.isEmpty() ? null : d;
    }

    private Double yearsOf(Resume r) {
        JsonNode p = json.read(r.getParsedJson());
        return (p != null && p.hasNonNull("total_years")) ? p.get("total_years").asDouble() : null;
    }
}
