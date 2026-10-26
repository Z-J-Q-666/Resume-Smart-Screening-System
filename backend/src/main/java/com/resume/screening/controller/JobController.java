package com.resume.screening.controller;

import com.resume.screening.common.ApiResponse;
import com.resume.screening.dto.JobRequest;
import com.resume.screening.dto.StatusRequest;
import com.resume.screening.dto.Views;
import com.resume.screening.service.ApplicationService;
import com.resume.screening.service.JobService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

@RestController
@RequestMapping("/api/jobs")
@RequiredArgsConstructor
public class JobController {

    private final JobService jobService;
    private final ApplicationService applicationService;

    @PostMapping
    public ApiResponse<Map<String, Long>> create(@Valid @RequestBody JobRequest req) {
        return ApiResponse.ok(Map.of("jobId", jobService.create(req)));
    }

    @GetMapping
    public ApiResponse<Views.PageView<Views.JobView>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) Integer status) {
        return ApiResponse.ok(jobService.list(page, size, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.JobView> detail(@PathVariable long id) {
        return ApiResponse.ok(jobService.detail(id));
    }

    @PutMapping("/{id}")
    public ApiResponse<Void> update(@PathVariable long id, @Valid @RequestBody JobRequest req) {
        jobService.update(id, req);
        return ApiResponse.ok();
    }

    @PutMapping("/{id}/status")
    public ApiResponse<Void> updateStatus(@PathVariable long id, @Valid @RequestBody StatusRequest req) {
        jobService.updateStatus(id, req.status());
        return ApiResponse.ok();
    }

    @GetMapping("/{id}/applications")
    public ApiResponse<Views.PageView<Views.BoardItem>> board(
            @PathVariable long id,
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "20") long size,
            @RequestParam(required = false) BigDecimal minScore,
            @RequestParam(required = false) String degree) {
        return ApiResponse.ok(applicationService.board(id, page, size, minScore, degree));
    }

    /** TOP N 快速通道（走 Redis 排行榜，缺失时从 MySQL 重建） */
    @GetMapping("/{id}/top")
    public ApiResponse<List<Views.JobTopItem>> top(@PathVariable long id,
                                                   @RequestParam(defaultValue = "20") int n) {
        return ApiResponse.ok(applicationService.top(id, n));
    }
}
