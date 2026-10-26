package com.resume.screening.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;

import java.util.List;

public record JobRequest(
        @NotBlank(message = "岗位名称不能为空")
        @Size(max = 100, message = "岗位名称不超过 100 字")
        String title,

        @Size(max = 100, message = "部门名称不超过 100 字")
        String department,

        String educationReq,

        @Min(value = 0, message = "年限要求不能小于 0")
        @Max(value = 30, message = "年限要求不能大于 30")
        Integer expYearsReq,

        @NotEmpty(message = "必备技能至少 1 项")
        @Size(max = 20, message = "必备技能最多 20 项")
        List<String> mustSkills,

        List<String> plusSkills,

        String jdText
) {
}
