package com.resume.screening.dto;

import lombok.Data;

/** 简历 → 投递过的岗位标题，用于简历库列表展示 */
@Data
public class ResumeJobTag {
    private Long resumeId;
    private String titles;
}
