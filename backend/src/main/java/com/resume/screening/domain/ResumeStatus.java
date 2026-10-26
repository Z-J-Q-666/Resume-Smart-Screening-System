package com.resume.screening.domain;

/** 简历状态机：PENDING → PARSING → EXTRACTED / FAILED，FAILED 可手动回到 PENDING */
public enum ResumeStatus {
    PENDING,
    PARSING,
    EXTRACTED,
    FAILED
}
