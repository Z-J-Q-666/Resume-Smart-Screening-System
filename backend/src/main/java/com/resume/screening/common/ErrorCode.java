package com.resume.screening.common;

import lombok.Getter;

/**
 * 错误码与 HTTP 状态码的对应关系见需求 §6.4。
 */
@Getter
public enum ErrorCode {

    BAD_FILE(1001, 400, "文件格式不支持或超过大小限制"),
    NOT_FOUND(1002, 404, "资源不存在"),
    RESUME_NOT_READY(1003, 409, "简历尚未解析完成，无法投递"),
    JOB_CLOSED(1004, 409, "岗位已关闭"),
    MUST_SKILL_EMPTY(1005, 400, "必备技能不能为空"),
    TOO_MANY_REQUESTS(1006, 429, "上传过于频繁，请稍后再试"),
    BAD_REQUEST(1007, 400, "请求参数不合法"),
    INTERNAL(1500, 500, "服务内部错误"),

    // AI 侧错误，透传自 Python 服务
    NO_TEXT_LAYER(2001, 500, "未检测到文本层"),
    LLM_FAILURE(2002, 500, "LLM 调用失败"),
    SCHEMA_VIOLATION(2003, 500, "抽取结果不符合 Schema");

    private final int code;
    private final int httpStatus;
    private final String defaultMessage;

    ErrorCode(int code, int httpStatus, String defaultMessage) {
        this.code = code;
        this.httpStatus = httpStatus;
        this.defaultMessage = defaultMessage;
    }

    public static ErrorCode of(int code) {
        for (ErrorCode e : values()) {
            if (e.code == code) {
                return e;
            }
        }
        return INTERNAL;
    }
}
