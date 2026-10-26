package com.resume.screening.controller;

import com.resume.screening.common.ApiResponse;
import com.resume.screening.dto.Views;
import com.resume.screening.service.PublicApplyService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

/**
 * 求职者侧入口，不需要登录（官网投递页用）。
 *
 * 只放两件事：看岗位、交简历。任何后台数据的读取、列表、统计都不在这里，
 * 避免把 HR 的能力从公网暴露出去。
 */
@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final PublicApplyService publicApplyService;

    @GetMapping("/jobs/{id}")
    public ApiResponse<Views.PublicJobView> job(@PathVariable long id) {
        return ApiResponse.ok(publicApplyService.job(id));
    }

    @PostMapping("/applications")
    public ApiResponse<Views.PublicApplyResult> apply(
            @RequestParam long jobId,
            @RequestParam(required = false) String name,
            @RequestParam(required = false) String phone,
            @RequestParam(required = false) String email,
            @RequestPart("file") MultipartFile file,
            HttpServletRequest request) {
        return ApiResponse.ok(publicApplyService.apply(jobId, name, phone, email, file, clientIp(request)));
    }

    private static String clientIp(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // 取第一个，后面是代理链
            int comma = forwarded.indexOf(',');
            return (comma > 0 ? forwarded.substring(0, comma) : forwarded).trim();
        }
        String real = request.getHeader("X-Real-IP");
        if (real != null && !real.isBlank()) {
            return real.trim();
        }
        return request.getRemoteAddr();
    }
}
