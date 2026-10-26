package com.resume.screening.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 岗位列表用的聚合统计（一次 GROUP BY 拿全，避免 N+1） */
@Data
public class JobStat {
    private Long jobId;
    private Long applicationCount;
    private BigDecimal avgScore;
}
