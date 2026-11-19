package com.resume.screening.dto;

/**
 * 自然语言查询入参。
 *
 * @param question       问题本身
 * @param conversationId 上一轮返回的会话 ID。不传就是新对话；
 *                       传了就能接着问"那第二个人呢"
 */
public record AgentQueryRequest(String question, String conversationId) {
}
