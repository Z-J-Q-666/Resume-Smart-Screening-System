package com.resume.screening.mq;

/**
 * resume-extract 消息体（字段名 camelCase，见 docs/message-contract.md）
 */
public record ExtractMessage(
        String messageId,
        Long resumeId,
        String filePath,
        int attempt,
        long timestamp
) {
}
