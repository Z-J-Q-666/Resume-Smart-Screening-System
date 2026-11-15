package com.resume.screening.dto;

import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;

/** 接口出参统一放这里，避免 Controller 里到处 new Map */
public final class Views {

    private Views() {
    }

    // ------------------------------------------------------------------ 岗位

    public record JobView(Long id,
                          String title,
                          String department,
                          String educationReq,
                          Integer expYearsReq,
                          List<String> mustSkills,
                          List<String> plusSkills,
                          String jdText,
                          Integer status,
                          LocalDateTime createdAt,
                          LocalDateTime updatedAt,
                          Long applicationCount,
                          BigDecimal avgScore) {
    }

    // ------------------------------------------------------------------ 简历

    public record UploadResult(Long resumeId, String status, boolean duplicated) {
    }

    public record BatchItem(String fileName, Long resumeId, String status, boolean duplicated, String error) {
    }

    public record ResumeStatusView(Long resumeId, String status, String errorMsg, String candidateName) {
    }

    public record ResumeDetailView(Long id,
                                   String candidateName,
                                   String phone,
                                   String email,
                                   String fileName,
                                   String status,
                                   String errorMsg,
                                   Integer costMs,
                                   LocalDateTime createdAt,
                                   JsonNode profile) {
    }

    public record ResumeListItem(Long id,
                                 String candidateName,
                                 String phone,
                                 String email,
                                 String fileName,
                                 String status,
                                 String errorMsg,
                                 Integer costMs,
                                 LocalDateTime createdAt,
                                 /** 投递过的岗位标题，顿号分隔；空串表示还没投递 */
                                 String appliedJobs) {
    }

    // -------------------------------------------------------------- 官网投递

    /** 对外暴露的岗位信息：只给求职者该看的字段 */
    public record PublicJobView(Long id,
                                String title,
                                String department,
                                String educationReq,
                                Integer expYearsReq,
                                List<String> mustSkills,
                                List<String> plusSkills,
                                String jdText,
                                boolean open) {
    }

    /**
     * 官网投递结果。刻意只回这三样：不暴露 resumeId / applicationId，
     * 求职者不需要、也不该拿到后台的主键。
     */
    public record PublicApplyResult(boolean accepted, boolean duplicated, String message) {
    }

    // ------------------------------------------------------------------ 投递

    public record ApplyResult(Long applicationId, String status) {
    }

    public record BreakdownView(int educationScore,
                                int expScore,
                                int skillScore,
                                int ruleScore,
                                BigDecimal semanticScore,
                                Weights weights) {
    }

    public record Weights(double rule, double semantic) {
    }

    public record ApplicationDetailView(Long applicationId,
                                        Long jobId,
                                        String jobTitle,
                                        Long resumeId,
                                        String candidateName,
                                        String status,
                                        BigDecimal score,
                                        String errorMsg,
                                        BreakdownView breakdown,
                                        List<String> hitSkills,
                                        List<String> missSkills,
                                        String reason,
                                        JsonNode profile,
                                        LocalDateTime createdAt) {
    }

    public record BoardItem(Long applicationId,
                            Long resumeId,
                            String candidateName,
                            String phone,
                            String email,
                            String degree,
                            BigDecimal totalYears,
                            BigDecimal score,
                            BigDecimal ruleScore,
                            BigDecimal semanticScore,
                            Integer hitCount,
                            Integer mustCount,
                            String status,
                            String reason,
                            List<String> hitSkills,
                            List<String> missSkills,
                            String errorMsg,
                            LocalDateTime createdAt) {
    }

    public record PageView<T>(long total, long page, long size, List<T> records) {
    }

    public record JobTopItem(Long resumeId,
                             String candidateName,
                             String degree,
                             BigDecimal totalYears,
                             BigDecimal score,
                             String source) {
    }

    // ------------------------------------------------------------------ AI 统计

    /**
     * 从 ai_call_log 聚合出来的指标。README 里"抽取耗时""单份成本""缓存命中率"
     * 就是这张接口的输出，不再靠手抄日志。
     */
    public record AiStatsView(long totalCalls,
                              long extractCalls,
                              long matchCalls,
                              long matchCacheHits,
                              double cacheHitRate,
                              long promptTokens,
                              long completionTokens,
                              long totalTokens,
                              double avgCostMs,
                              double estCostYuan,
                              String priceNote) {
    }
}
