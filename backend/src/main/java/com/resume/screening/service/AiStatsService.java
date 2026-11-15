package com.resume.screening.service;

import com.resume.screening.config.AppProperties;
import com.resume.screening.dto.AiTaskStat;
import com.resume.screening.dto.Views;
import com.resume.screening.mapper.AiCallLogMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.List;

/**
 * AI 调用统计。
 *
 * <p>存在的意义：README 里的"抽取耗时""单份成本""缓存命中率"必须能从系统里查出来，
 * 而不是从日志里手抄。手抄的数字过两天就过期，接口出来的数字随时可复现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AiStatsService {

    private final AiCallLogMapper aiCallLogMapper;
    private final AppProperties props;

    public Views.AiStatsView summary(int days) {
        int window = Math.max(1, Math.min(days, 365));
        LocalDateTime since = LocalDateTime.now().minusDays(window);

        List<AiTaskStat> stats;
        try {
            stats = aiCallLogMapper.selectTaskStats(since);
        } catch (Exception e) {
            // 统计挂了不该让接口 500，返回零值让调用方自己判断
            log.warn("查询 AI 统计失败 window={}d err={}", window, e.getMessage());
            return empty();
        }

        long extractCalls = 0;
        long matchCalls = 0;
        long cacheHits = 0;
        long promptTokens = 0;
        long completionTokens = 0;
        double weightedCostMs = 0;

        for (AiTaskStat s : stats) {
            long calls = nz(s.getCalls());
            long pt = nz(s.getPromptTokens());
            long ct = nz(s.getCompletionTokens());
            double avg = s.getAvgCostMs() == null ? 0 : s.getAvgCostMs();

            promptTokens += pt;
            completionTokens += ct;
            // 各 task 的均值不能直接相加，要按调用数加权回总量
            weightedCostMs += avg * calls;

            if ("EXTRACT".equalsIgnoreCase(s.getTask())) {
                extractCalls += calls;
            } else if ("MATCH".equalsIgnoreCase(s.getTask())) {
                matchCalls += calls;
                cacheHits += nz(s.getCacheHits());
            }
        }

        long total = extractCalls + matchCalls;
        double avgCostMs = total == 0 ? 0 : weightedCostMs / total;
        double hitRate = matchCalls == 0 ? 0 : (double) cacheHits / matchCalls;

        double priceIn = props.getAi().getPricePromptPerMillion();
        double priceOut = props.getAi().getPriceCompletionPerMillion();
        double costYuan = promptTokens / 1_000_000.0 * priceIn
                + completionTokens / 1_000_000.0 * priceOut;

        return new Views.AiStatsView(
                total, extractCalls, matchCalls, cacheHits,
                round(rateOrZero(hitRate), 4),
                promptTokens, completionTokens, promptTokens + completionTokens,
                round(avgCostMs, 1), round(costYuan, 4),
                String.format("按输入 %.2f 元/百万、输出 %.2f 元/百万 token 折算；单价在 app.ai.price-* 调整",
                        priceIn, priceOut)
        );
    }

    private Views.AiStatsView empty() {
        return new Views.AiStatsView(0, 0, 0, 0, 0, 0, 0, 0, 0, 0, "暂无数据或统计查询失败");
    }

    private static long nz(Long v) {
        return v == null ? 0 : v;
    }

    private static double rateOrZero(double v) {
        return Double.isFinite(v) ? v : 0;
    }

    private static double round(double v, int scale) {
        double factor = Math.pow(10, scale);
        return Math.round(v * factor) / factor;
    }
}
