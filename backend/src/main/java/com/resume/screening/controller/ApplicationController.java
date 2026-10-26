package com.resume.screening.controller;

import com.resume.screening.common.ApiResponse;
import com.resume.screening.dto.ApplyRequest;
import com.resume.screening.dto.Views;
import com.resume.screening.service.ApplicationService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/applications")
@RequiredArgsConstructor
public class ApplicationController {

    private final ApplicationService applicationService;

    @PostMapping
    public ApiResponse<Views.ApplyResult> apply(@Valid @RequestBody ApplyRequest req) {
        return ApiResponse.ok(applicationService.apply(req.jobId(), req.resumeId()));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.ApplicationDetailView> detail(@PathVariable long id) {
        return ApiResponse.ok(applicationService.detail(id));
    }
}
