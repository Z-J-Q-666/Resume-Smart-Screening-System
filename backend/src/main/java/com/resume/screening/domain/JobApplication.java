package com.resume.screening.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.math.BigDecimal;
import java.time.LocalDateTime;

@Data
@TableName("job_application")
public class JobApplication {

    @TableId(type = IdType.AUTO)
    private Long id;

    private Long jobId;
    private Long resumeId;

    private BigDecimal ruleScore;
    private BigDecimal semanticScore;
    private BigDecimal score;

    /** JSON 字符串数组 */
    private String hitSkills;
    /** JSON 字符串数组 */
    private String missSkills;

    private String reason;
    private String status;
    private String errorMsg;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
