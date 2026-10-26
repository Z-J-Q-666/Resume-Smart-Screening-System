package com.resume.screening.domain;

import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.Data;

import java.time.LocalDateTime;

@Data
@TableName("job")
public class Job {

    @TableId(type = IdType.AUTO)
    private Long id;

    private String title;
    private String department;
    private String educationReq;
    private Integer expYearsReq;

    /** JSON 字符串数组 */
    private String mustSkills;
    /** JSON 字符串数组 */
    private String plusSkills;

    private String jdText;
    /** 1 开放 0 关闭 */
    private Integer status;

    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
