package com.resume.screening.controller;

import com.resume.screening.common.ApiResponse;
import com.resume.screening.dto.Views;
import com.resume.screening.service.ResumeService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import java.util.List;

/**
 * 上传接口必须秒回（<500ms），解析一律走 MQ 异步，见需求 FR-RES-03。
 */
@RestController
@RequestMapping("/api/resumes")
@RequiredArgsConstructor
public class ResumeController {

    private final ResumeService resumeService;

    @PostMapping
    public ApiResponse<Views.UploadResult> upload(
            @RequestPart("file") MultipartFile file,
            @RequestHeader(value = "X-User-Id", defaultValue = "hr") String userId) {
        return ApiResponse.ok(resumeService.upload(file, userId));
    }

    @PostMapping("/batch")
    public ApiResponse<List<Views.BatchItem>> batchUpload(
            @RequestPart("files") List<MultipartFile> files,
            @RequestHeader(value = "X-User-Id", defaultValue = "hr") String userId) {
        return ApiResponse.ok(resumeService.batchUpload(files, userId));
    }

    @GetMapping
    public ApiResponse<Views.PageView<Views.ResumeListItem>> list(
            @RequestParam(defaultValue = "1") long page,
            @RequestParam(defaultValue = "10") long size,
            @RequestParam(required = false) String status) {
        return ApiResponse.ok(resumeService.list(page, size, status));
    }

    @GetMapping("/{id}")
    public ApiResponse<Views.ResumeDetailView> detail(@PathVariable long id) {
        return ApiResponse.ok(resumeService.detail(id));
    }

    /** 前端轮询入口，Redis 优先，缺失回查 MySQL */
    @GetMapping("/{id}/status")
    public ApiResponse<Views.ResumeStatusView> status(@PathVariable long id) {
        return ApiResponse.ok(resumeService.status(id));
    }

    @PostMapping("/{id}/retry")
    public ApiResponse<Views.UploadResult> retry(@PathVariable long id) {
        return ApiResponse.ok(resumeService.retry(id));
    }
}
