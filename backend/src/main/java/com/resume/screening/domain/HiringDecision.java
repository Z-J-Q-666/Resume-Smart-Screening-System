package com.resume.screening.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 录用决策。语义分校准的锚点——没有"什么算好"，模型只能凭感觉打分。
 *
 * <p>一条投递只保留一个结论（uk_application），改判就是覆盖。
 */
@Data
@TableName("hiring_decision")
public class HiringDecision {

    public static final String HIRED = "HIRED";
    public static final String REJECTED = "REJECTED";

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long applicationId;

    /** 冗余字段：检索"这个岗位录取过谁"时免一次 join */
    private Long jobId;
    private Long resumeId;

    /** HIRED / REJECTED */
    private String outcome;

    private String note;
    private String decidedBy;
    private LocalDateTime decidedAt;
}
