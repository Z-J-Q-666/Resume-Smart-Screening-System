package com.resume.screening.dto;

import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/**
 * 候选人看板行：投递记录 + 简历上冗余出来的展示字段。
 * 用一条 JOIN 查出，避免 N+1；学历过滤也放在 SQL 里，保证分页准确。
 */
@Data
public class BoardRow {
    private Long id;
    private Long jobId;
    private Long resumeId;
    private BigDecimal ruleScore;
    private BigDecimal semanticScore;
    private BigDecimal score;
    private String hitSkills;
    private String missSkills;
    private String reason;
    private String status;
    private String errorMsg;

    private String candidateName;
    private String phone;
    private String email;
    private String degree;
    private BigDecimal totalYears;
    private LocalDateTime createdAt;
}
