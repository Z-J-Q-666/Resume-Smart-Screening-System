package com.resume.screening.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("resume")
public class Resume {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String candidateName;
    private String phone;
    private String email;

    private String fileName;
    /** 相对 uploads 根的路径 */
    private String filePath;
    /** 文件内容 SHA-256 */
    private String fileHash;

    private String rawText;
    /** LLM 抽取的结构化 Profile（JSON 原文） */
    private String parsedJson;

    private String status;
    private String errorMsg;
    private Integer costMs;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
