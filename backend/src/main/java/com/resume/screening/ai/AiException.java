package com.resume.screening.ai;

import lombok.Getter;

@Getter
public class AiException extends RuntimeException {

    /** 对应需求 §6.4 的 AI 侧错误码 */
    private final int code;
    /** 是否值得重试：无文本层、Schema 不合规重试无意义 */
    private final boolean retryable;

    public AiException(int code, String message, boolean retryable) {
        super(message);
        this.code = code;
        this.retryable = retryable;
    }

    public static AiException noTextLayer(String msg) {
        return new AiException(2001, msg, false);
    }

    public static AiException llmFailure(String msg) {
        return new AiException(2002, msg, true);
    }

    public static AiException schemaViolation(String msg) {
        return new AiException(2003, msg, false);
    }
}
