package com.resume.screening.agent.tools;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.agent.AgentTool;
import com.resume.screening.common.Json;
import com.resume.screening.domain.Job;
import com.resume.screening.dto.JobStat;
import com.resume.screening.mapper.HiringDecisionMapper;
import com.resume.screening.mapper.JobApplicationMapper;
import com.resume.screening.mapper.JobMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** 岗位层面的统计。回答"哪个岗位招得急""这个岗位平均分多少"。 */
@Component
@RequiredArgsConstructor
public class JobStatsTool implements AgentTool {

    private final JobApplicationMapper applicationMapper;
    private final HiringDecisionMapper decisionMapper;
    private final JobMapper jobMapper;
    private final Json json;

    @Override
    public String name() {
        return "get_job_stats";
    }

    @Override
    public String description() {
        return "查岗位的投递量、平均分、录用人数。不传 jobId 返回所有岗位的概览，"
                + "用于回答『哪个岗位收到的简历最多』『各岗位平均分对比』这类问题。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("jobId", Map.of("type", "integer", "description", "岗位 ID，不传则返回全部岗位概览"));
        return Map.of("type", "object", "properties", props);
    }

    @Override
    public String execute(JsonNode args) {
        Long jobId = args.hasNonNull("jobId") ? args.get("jobId").asLong() : null;

        List<JobStat> stats = applicationMapper.selectJobStats();
        if (jobId != null) {
            stats = stats.stream().filter(s -> jobId.equals(s.getJobId())).toList();
        }
        if (stats.isEmpty()) {
            return json.write(Map.of("error", jobId == null ? "暂无投递数据" : "该岗位没有投递记录：" + jobId));
        }

        List<Long> ids = stats.stream().map(JobStat::getJobId).toList();
        Map<Long, String> titles = jobMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Job::getId, j -> j.getTitle() == null ? "" : j.getTitle(), (a, b) -> a));

        List<Map<String, Object>> items = new ArrayList<>();
        for (JobStat s : stats) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("jobId", s.getJobId());
            m.put("title", titles.getOrDefault(s.getJobId(), ""));
            m.put("applications", s.getApplicationCount());
            m.put("avgScore", s.getAvgScore() == null ? null
                    : Math.round(s.getAvgScore().doubleValue() * 10) / 10.0);
            // 岗位数量很少，逐个查录用数比写一条复杂聚合 SQL 更省事
            m.put("hired", decisionMapper.selectHiredWithScore(s.getJobId()).size());
            items.add(m);
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("count", items.size());
        payload.put("jobs", items);
        return json.write(payload);
    }
}
