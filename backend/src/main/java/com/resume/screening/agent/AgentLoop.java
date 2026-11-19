package com.resume.screening.agent;

import com.resume.screening.ai.AiClient;
import com.resume.screening.common.Json;
import com.resume.screening.dto.Views;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Agent 主循环。
 *
 * <p>为什么循环放在这里而不是 Python：工具要查数据库，而 AI 服务刻意不碰库。
 * 放在业务侧，每一轮的工具调用都能落日志、进 trace、被单测覆盖；
 * 放 Python 就得反向回调 Java，链路绕且难排查。
 *
 * <p>上限 5 轮：正常问题 1-3 轮就够。超过说明模型在原地打转，
 * 继续跑只是烧钱，不如直接告诉用户"说具体点"。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentLoop {

    private static final int MAX_STEPS = 5;
    private static final int ARG_MAX = 300;

    /**
     * 规则不放在 system 里，而是拼进第一条 user 消息。
     *
     * <p>实测发现：同样四个工具，用 system 传规则时 DeepSeek 只回一句"我先查一下"，
     * 从不真正发起 tool_call；把规则并进 user 消息后就正常了。
     * 生产环境换模型时要重新验证这一点，不值得把它当成通用结论。
     */
    private static final String RULES = """
            你在帮我查简历筛选系统的数据。要求：
            1. 你没有任何业务数据的记忆，所有事实必须来自工具返回结果。
            2. 所以先调用工具拿数据，再回答。不要先解释你打算怎么做。
            3. 数据够了就直接给结论，不要重复调用同一个工具。
            4. 回答先给结论再列数据；提到具体的人时带上 applicationId。
            5. 工具返回空就说明原因并建议放宽条件，绝不编造候选人和数字。
            6. 只输出自然语言和短列表，不要 JSON、SQL 或代码。

            """;

    private final AiClient aiClient;
    private final ToolRegistry registry;
    private final Json json;

    public Views.AgentAnswer query(String question) {
        List<Map<String, Object>> messages = new ArrayList<>();
        messages.add(Map.of("role", "user", "content", RULES + question));

        List<Views.AgentStep> trace = new ArrayList<>();
        List<Map<String, Object>> toolSpecs = registry.specs();

        for (int step = 1; step <= MAX_STEPS; step++) {
            // 首轮强制调工具：模型容易"先表态再动手"，required 能把它推回正轨。
            // 后续轮次放开，否则它会一直调工具、永远给不出答案。
            AiClient.ChatResult result = aiClient.chat(messages, toolSpecs, step == 1);

            if (result.toolCalls().isEmpty()) {
                String answer = result.content() == null || result.content().isBlank()
                        ? "我没能根据现有数据回答这个问题。可以换个问法，比如指定岗位或分数区间。"
                        : result.content();
                log.info("Agent 完成 steps={} tools={}", step, trace.size());
                return new Views.AgentAnswer(answer, trace, step, false);
            }

            // 把模型这一轮的"我要调这些工具"回填进对话
            messages.add(assistantMessage(result));

            for (AiClient.ChatToolCall call : result.toolCalls()) {
                long t0 = System.currentTimeMillis();
                String toolResult = registry.execute(call.name(), call.arguments());
                int costMs = (int) (System.currentTimeMillis() - t0);

                Map<String, Object> toolMsg = new LinkedHashMap<>();
                toolMsg.put("role", "tool");
                toolMsg.put("content", toolResult);
                // 键名用蛇形，与 Python 侧的 Pydantic 字段对齐
                toolMsg.put("tool_call_id", call.id());
                messages.add(toolMsg);

                trace.add(new Views.AgentStep(step, call.name(),
                        truncate(String.valueOf(call.arguments()), ARG_MAX),
                        truncate(toolResult, ARG_MAX), costMs));
            }
        }

        log.warn("Agent 达到步数上限 question={}", truncate(question, 60));
        return new Views.AgentAnswer(
                "这个问题需要的分析步数超过上限（" + MAX_STEPS + " 步），我先停下了。"
                        + "建议把问题说得更具体一些，比如指定岗位、分数区间或技能。",
                trace, MAX_STEPS, true);
    }

    private Map<String, Object> assistantMessage(AiClient.ChatResult result) {
        List<Map<String, Object>> calls = new ArrayList<>();
        for (AiClient.ChatToolCall c : result.toolCalls()) {
            Map<String, Object> tc = new LinkedHashMap<>();
            tc.put("id", c.id());
            tc.put("name", c.name());
            // LangChain 的 tool_call 结构用 args，不是 arguments
            tc.put("args", c.arguments());
            calls.add(tc);
        }
        Map<String, Object> msg = new LinkedHashMap<>();
        msg.put("role", "assistant");
        msg.put("content", result.content() == null ? "" : result.content());
        msg.put("tool_calls", calls);
        return msg;
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
