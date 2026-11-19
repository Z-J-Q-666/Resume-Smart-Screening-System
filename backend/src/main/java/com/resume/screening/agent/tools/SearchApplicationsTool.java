package com.resume.screening.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.agent.AgentTool;
import com.resume.screening.common.Json;
import com.resume.screening.dto.AgentApplicationRow;
import com.resume.screening.mapper.JobApplicationMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 查投递记录。Agent 用得最多的一把工具。 */
@Component
@RequiredArgsConstructor
public class SearchApplicationsTool implements AgentTool {

    private final JobApplicationMapper applicationMapper;
    private final Json json;

    @Override
    public String name() {
        return "search_applications";
    }

    @Override
    public String description() {
        return "按岗位、综合分区间、最少年限、技能关键词筛选候选人投递记录，按综合分从高到低返回。"
                + "回答『谁投了 Java 岗』『分数最高的几个人』『有没有做过 Redis 的人』这类问题时用它。"
                + "不传 jobId 则跨岗位查询。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jobId", Map.of("type", "integer", "description", "岗位 ID，不传则查全部岗位"));
        props.put("minScore", Map.of("type", "number", "description", "综合分下限（含）"));
        props.put("maxScore", Map.of("type", "number", "description", "综合分上限（含）"));
        props.put("minYears", Map.of("type", "number", "description", "最少工作年限"));
        props.put("skill", Map.of("type", "string", "description", "技能关键词，如 Redis、Spring Boot，不区分大小写"));
        props.put("limit", Map.of("type", "integer", "description", "返回条数，默认 10，最多 30"));
        return Map.of("type", "object", "properties", props);
    }

    @Override
    public String execute(JsonNode args) {
        Long jobId = args.hasNonNull("jobId") ? args.get("jobId").asLong() : null;
        BigDecimal minScore = args.hasNonNull("minScore") ? BigDecimal.valueOf(args.get("minScore").asDouble()) : null;
        BigDecimal maxScore = args.hasNonNull("maxScore") ? BigDecimal.valueOf(args.get("maxScore").asDouble()) : null;
        BigDecimal minYears = args.hasNonNull("minYears") ? BigDecimal.valueOf(args.get("minYears").asDouble()) : null;
        int limit = Math.max(1, Math.min(args.path("limit").asInt(10), 30));

        String skill = args.path("skill").asText("").trim();
        // 技能过滤在内存里做：JSON 数组的模糊匹配写进 SQL 又长又难调，几百条数据不值得
        int fetch = skill.isEmpty() ? limit : Math.min(limit * 5, 150);

        List<AgentApplicationRow> rows = applicationMapper.searchForAgent(
                jobId, minScore, maxScore, minYears, fetch);

        List<Map<String, Object>> out = new ArrayList<>();
        for (AgentApplicationRow r : rows) {
            if (!skill.isEmpty() && !hasSkill(r.getParsedJson(), skill)) {
                continue;
            }
            out.add(toItem(r));
            if (out.size() >= limit) {
                break;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", out.size());
        payload.put("items", out);
        if (out.isEmpty()) {
            payload.put("hint", "没有符合条件的结果。可以放宽分数/年限条件，或去掉技能关键词再试。");
        }
        return json.write(payload);
    }

    private Map<String, Object> toItem(AgentApplicationRow r) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("applicationId", r.getApplicationId());
        m.put("jobId", r.getJobId());
        m.put("candidateName", r.getCandidateName());
        m.put("degree", r.getDegree());
        m.put("totalYears", r.getTotalYears());
        m.put("ruleScore", r.getRuleScore());
        m.put("semanticScore", r.getSemanticScore());
        m.put("score", r.getScore());
        m.put("status", r.getStatus());
        // reason 截断：完整理由动辄 100 字，十条就是 1000 token，得不偿失
        String reason = r.getReason();
        if (reason != null && reason.length() > 60) {
            reason = reason.substring(0, 60) + "…";
        }
        m.put("reason", reason);
        return m;
    }

    private boolean hasSkill(String parsedJson, String keyword) {
        if (parsedJson == null) {
            return false;
        }
        JsonNode profile = json.read(parsedJson);
        JsonNode skills = profile == null ? null : profile.path("skills");
        if (skills == null || !skills.isArray()) {
            return false;
        }
        String kw = keyword.toLowerCase();
        for (JsonNode s : skills) {
            if (s.asText("").toLowerCase().contains(kw)) {
                return true;
            }
        }
        return false;
    }
}
