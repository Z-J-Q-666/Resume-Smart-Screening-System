package com.resume.screening.dto;

import lombok.Data;

/** ai_call_log 按 task 聚合的一行，供 {@code AiStatsService} 拼装统计结果。 */
@Data
public class AiTaskStat {

    /** EXTRACT / MATCH */
    private String task;

    private Long calls;
    private Long cacheHits;
    private Long promptTokens;
    private Long completionTokens;
    private Double avgCostMs;
}
