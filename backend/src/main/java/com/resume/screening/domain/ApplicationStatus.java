package com.resume.screening.domain;

/**
 * 投递状态机：
 *   WAITING（已投递，等简历解析）→ SCORING（打分中）→ SCORED / FAILED
 * 官网投递进来时简历还没解析，必须先等；HR 后台给已解析简历指派岗位则直接进 SCORING。
 */
public enum ApplicationStatus {
    WAITING,
    SCORING,
    SCORED,
    FAILED
}
