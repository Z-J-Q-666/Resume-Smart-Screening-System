package com.resume.screening.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

/**
 * 简历画像的向量。只嵌 profile 的语义摘要，不嵌原文——
 * 校准要回答的是"这个人像不像被录取过的那批人"，不是"这两份简历文字像不像"。
 *
 * <p>vector 按 JSON 字符串存取，和项目里其他 JSON 列保持一致（排查时直接看库就懂）。
 */
@Data
@TableName("resume_embedding")
public class ResumeEmbedding {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long resumeId;

    /** 嵌入模型名。换模型必须重算，所以进唯一键 */
    private String model;

    private Integer dim;

    /** float 数组的 JSON 字符串 */
    private String vector;

    /** profile JSON 的 sha256：profile 重抽过才需要重算向量 */
    private String sourceHash;

    private LocalDateTime createdAt;
}
