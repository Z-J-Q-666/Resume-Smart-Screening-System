package com.resume.screening.dto;

/**
 * 录用决策入参。
 *
 * @param outcome HIRED / REJECTED
 * @param note    决策备注，会保存下来供后续回溯
 */
public record DecisionRequest(String outcome, String note) {
}
