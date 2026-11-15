package com.resume.screening.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 一次 LLM 调用的流水。
 *
 * <p>为什么单独建表而不是只打日志：日志能查单次，但回答不了"一千份简历花多少钱"、
 * "改完 prompt 之后平均多消耗多少 token"、"缓存到底省了多少"。这些是要放进 README 的数字。
 *
 * <p>命中语义分缓存时也落一行（cacheHit=1、tokens=0），否则命中率的分母是错的。
 */
@Data
@TableName("ai_call_log")
public class AiCallLog {

    @TableId(type = IdType.AUTO)
    private Long id;

    /** EXTRACT / MATCH */
    private String task;

    private Long resumeId;
    private Long applicationId;
    private Long jobId;

    private String model;

    /** prompt 版本，和 resume.prompt_version 对得上 */
    private String promptVersion;

    private Integer promptTokens;
    private Integer completionTokens;

    /** 本次调用总耗时（含网络与 Schema 重试） */
    private Integer costMs;

    /** 1 = 命中语义分缓存，没有真正调用 LLM */
    private Integer cacheHit;

    private Integer success;

    /** 失败时的 AI 错误码：2001 无文本层 / 2002 LLM 失败 / 2003 Schema 不合规 */
    private Integer errorCode;

    private LocalDateTime createdAt;
}
