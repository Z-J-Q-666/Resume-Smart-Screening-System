package com.resume.screening.dto;

import lombok.Data;

import java.math.BigDecimal;

/** Agent 工具用的投递行：比看板的 BoardRow 多带 parsed_json，方便工具自己过滤技能。 */
@Data
public class AgentApplicationRow {

    private Long applicationId;
    private Long jobId;
    private Long resumeId;
    private BigDecimal ruleScore;
    private BigDecimal semanticScore;
    private BigDecimal score;
    private String status;
    private String reason;
    private String candidateName;
    private String degree;
    private BigDecimal totalYears;
    private String parsedJson;
}
