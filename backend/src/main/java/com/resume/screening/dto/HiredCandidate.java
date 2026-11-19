package com.resume.screening.dto;

import lombok.Data;

import java.math.BigDecimal;

/** 某岗位已录用的候选人：用于检索 few-shot 锚点。 */
@Data
public class HiredCandidate {

    private Long resumeId;

    /** 当时系统给的语义分，作为校准锚点的数值 */
    private BigDecimal semanticScore;
}
