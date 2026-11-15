package com.resume.screening.controller;

import com.resume.screening.common.ApiResponse;
import com.resume.screening.dto.Views;
import com.resume.screening.service.AiStatsService;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * 观测接口。属于后台数据，将来接入鉴权时必须一并纳入受保护范围（当前项目无鉴权）。
 */
@RestController
@RequestMapping("/api/stats")
@RequiredArgsConstructor
public class StatsController {

    private final AiStatsService aiStatsService;

    /** AI 调用统计：token、成本估算、缓存命中率、平均耗时 */
    @GetMapping("/ai")
    public ApiResponse<Views.AiStatsView> ai(@RequestParam(defaultValue = "30") int days) {
        return ApiResponse.ok(aiStatsService.summary(days));
    }
}
