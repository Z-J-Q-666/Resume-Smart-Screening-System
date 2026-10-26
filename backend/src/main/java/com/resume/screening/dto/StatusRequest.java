package com.resume.screening.dto;

import jakarta.validation.constraints.NotNull;

public record StatusRequest(@NotNull(message = "status 不能为空") Integer status) {
}
