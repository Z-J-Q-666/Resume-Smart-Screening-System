package com.resume.screening.controller;

import com.resume.screening.agent.AgentLoop;
import com.resume.screening.cache.AgentRateLimiter;
import com.resume.screening.common.ApiResponse;
import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.dto.AgentQueryRequest;
import com.resume.screening.dto.Views;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * HR 自然语言查询。
 *
 * <p>这是全项目最贵的一个接口：一次问句 3-5 轮 LLM、每轮上千 token，
 * 而且能查到全部候选人的姓名和联系方式。所以限流放在最前面，脏请求不该走到 LLM 那一步。
 *
 * <p>将来接鉴权时要纳入保护范围（当前项目无鉴权）。
 */
@RestController
@RequestMapping("/api/agent")
@RequiredArgsConstructor
public class AgentController {

    private static final int QUESTION_MAX = 300;

    private final AgentLoop agentLoop;
    private final AgentRateLimiter rateLimiter;

    @PostMapping("/query")
    public ApiResponse<Views.AgentAnswer> query(
            @RequestBody AgentQueryRequest req,
            @RequestHeader(value = "X-User-Id", defaultValue = "hr") String userId) {

        rateLimiter.check(userId);

        String q = req.question() == null ? "" : req.question().trim();
        if (q.isEmpty()) {
            throw new BizException(ErrorCode.BAD_REQUEST, "问题不能为空");
        }
        if (q.length() > QUESTION_MAX) {
            throw new BizException(ErrorCode.BAD_REQUEST, "问题太长，请控制在 " + QUESTION_MAX + " 字以内");
        }
        return ApiResponse.ok(agentLoop.query(q, req.conversationId()));
    }
}
