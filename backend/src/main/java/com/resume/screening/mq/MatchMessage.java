package com.resume.screening.mq;

/**
 * match-score 消息体（字段名 camelCase，见 docs/message-contract.md）
 */
public record MatchMessage(
        String messageId,
        Long applicationId,
        Long resumeId,
        Long jobId,
        long timestamp
) {
}
