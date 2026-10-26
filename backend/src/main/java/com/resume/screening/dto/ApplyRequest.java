package com.resume.screening.dto;

import jakarta.validation.constraints.NotNull;

public record ApplyRequest(
        @NotNull(message = "jobId 不能为空") Long jobId,
        @NotNull(message = "resumeId 不能为空") Long resumeId
) {
}
