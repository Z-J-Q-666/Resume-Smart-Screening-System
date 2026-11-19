package com.resume.screening.ai;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.stereotype.Component;
import org.springframework.util.LinkedMultiValueMap;
import org.springframework.util.MultiValueMap;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestTemplate;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.common.Json;
import com.resume.screening.config.AppProperties;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Python AI 服务的 HTTP 客户端。只做两件事：传 PDF 拿 Profile，传 Profile 拿语义分。
 * 所有异常都转成 AiException，并标明是否值得重试。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiClient {

    private final RestTemplate aiRestTemplate;
    private final AppProperties props;
    private final Json json;

    /** model / promptVersion 由 Python 侧随响应带回，落进 ai_call_log，是评估对比的锚点 */
    public record ExtractOutcome(String rawText, String profileJson, int costMs,
                                 int promptTokens, int completionTokens,
                                 String model, String promptVersion) {
    }

    public record MatchOutcome(int semanticScore, String reason, int costMs,
                               int promptTokens, int completionTokens,
                               String model, String promptVersion) {
    }

    /** /health 返回的版本信息，用于启动时校验 Java 与 Python 的 prompt 版本是否对齐 */
    public record HealthInfo(String model, String extractPromptVersion, String matchPromptVersion,
                             boolean embeddingAvailable, String embeddingModel) {
    }

    /** 打分锚点：同岗位历史录用者的一行摘要 + 当时的分 */
    public record Example(String digest, int score) {
    }

    /** 模型发起的一次工具调用 */
    public record ChatToolCall(String id, String name, JsonNode arguments) {
    }

    /** 一轮对话的结果：要么有 answer，要么有 toolCalls */
    public record ChatResult(String content, List<ChatToolCall> toolCalls, String finishReason) {
    }

    private record Envelope(int code, String message, JsonNode data) {
    }

    // ------------------------------------------------------------------ extract

    public ExtractOutcome extract(byte[] pdfBytes, String fileName) {
        MultiValueMap<String, Object> body = new LinkedMultiValueMap<>();
        body.add("file", new ByteArrayResource(pdfBytes) {
            @Override
            public String getFilename() {
                return fileName;
            }
        });

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.MULTIPART_FORM_DATA);

        Envelope env = post(props.getAi().getBaseUrl() + "/extract", new HttpEntity<>(body, headers));
        ensureOk(env);

        JsonNode data = env.data();
        String profileJson = json.write(data.path("profile"));
        if (profileJson == null || profileJson.isBlank()) {
            throw AiException.schemaViolation("AI 服务未返回 profile");
        }
        JsonNode tokens = data.path("tokens");
        return new ExtractOutcome(
                data.path("rawText").asText(""),
                profileJson,
                data.path("costMs").asInt(0),
                tokens.path("prompt").asInt(0),
                tokens.path("completion").asInt(0),
                data.path("model").asText("unknown"),
                data.path("promptVersion").asText("unknown")
        );
    }

    // -------------------------------------------------------------------- match

    public MatchOutcome match(String jobTitle,
                              String jdText,
                              List<String> mustSkills,
                              List<String> plusSkills,
                              JsonNode profile,
                              List<Example> examples) {

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("job_title", jobTitle == null ? "" : jobTitle);
        payload.put("jd_text", jdText == null ? "" : jdText);
        payload.put("must_skills", mustSkills == null ? List.of() : mustSkills);
        payload.put("plus_skills", plusSkills == null ? List.of() : plusSkills);
        payload.put("profile", json.mapper().convertValue(profile, Map.class));
        // 空列表等价于"没有参照"，Python 侧会跳过锚点段落，退回 v2 的打分逻辑
        payload.put("examples", examples == null ? List.of()
                : examples.stream().map(e -> Map.of("digest", e.digest(), "score", e.score())).toList());

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        long t0 = System.currentTimeMillis();
        Envelope env = post(props.getAi().getBaseUrl() + "/match", new HttpEntity<>(payload, headers));
        ensureOk(env);

        JsonNode data = env.data();
        int score = data.path("semanticScore").asInt(-1);
        if (score < 0 || score > 100) {
            throw AiException.schemaViolation("语义分超出 0-100 范围：" + score);
        }
        String reason = data.path("reason").asText("");
        JsonNode tokens = data.path("tokens");
        log.info("语义分 job={} score={} tokens={} costMs={}",
                jobTitle, score, tokens.path("total").asInt(0),
                System.currentTimeMillis() - t0);
        return new MatchOutcome(
                score,
                reason,
                data.path("costMs").asInt(0),
                tokens.path("prompt").asInt(0),
                tokens.path("completion").asInt(0),
                data.path("model").asText("unknown"),
                data.path("promptVersion").asText("unknown")
        );
    }

    // --------------------------------------------------------------------- chat

    /**
     * 跑一轮带工具的对话。
     *
     * <p>注意这里**只跑一轮**：返回的是"模型下一步想干什么"，不是最终答案。
     * 循环由 Java 侧的 AgentLoop 控制——工具要查库，而 AI 服务刻意不碰数据库。
     */
    public ChatResult chat(List<Map<String, Object>> messages, List<Map<String, Object>> tools,
                           boolean forceTool) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("messages", messages);
        payload.put("tools", tools == null ? List.of() : tools);
        payload.put("force_tool", forceTool);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Envelope env = post(props.getAi().getBaseUrl() + "/chat", new HttpEntity<>(payload, headers));
        ensureOk(env);

        JsonNode data = env.data();
        List<ChatToolCall> calls = new ArrayList<>();
        for (JsonNode c : data.path("toolCalls")) {
            calls.add(new ChatToolCall(
                    c.path("id").asText(""),
                    c.path("name").asText(""),
                    c.path("arguments")));
        }
        String content = data.hasNonNull("content") ? data.get("content").asText() : null;
        return new ChatResult(content, calls, data.path("finishReason").asText("stop"));
    }

    // ------------------------------------------------------------------- health

    /** 读 AI 服务版本。调用方自己容错——服务没起来是正常情况，不该阻断后端启动 */
    public HealthInfo health() {
        ResponseEntity<String> resp;
        try {
            resp = aiRestTemplate.getForEntity(props.getAi().getBaseUrl() + "/health", String.class);
        } catch (Exception e) {
            throw AiException.llmFailure("AI 服务健康检查失败：" + e.getMessage());
        }
        JsonNode root = json.read(resp.getBody());
        JsonNode data = root == null ? null : root.path("data");
        if (data == null || data.isMissingNode() || data.isNull()) {
            throw AiException.llmFailure("健康检查返回结构异常");
        }
        return new HealthInfo(
                data.path("model").asText("unknown"),
                data.path("extractPromptVersion").asText("unknown"),
                data.path("matchPromptVersion").asText("unknown"),
                data.path("embeddingAvailable").asBoolean(false),
                data.path("embeddingModel").asText("")
        );
    }

    // -------------------------------------------------------------------- embed

    /**
     * 批量向量化。服务未配置时 Python 返回 2004，这里抛出去由调用方降级——
     * 向量化只是"锦上添花"，不该让打分链路上的任何一环因此失败。
     */
    public List<float[]> embed(List<String> texts) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("texts", texts);

        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);

        Envelope env = post(props.getAi().getBaseUrl() + "/embed", new HttpEntity<>(payload, headers));
        ensureOk(env);

        List<float[]> out = new ArrayList<>();
        for (JsonNode arr : env.data().path("vectors")) {
            float[] v = new float[arr.size()];
            for (int i = 0; i < arr.size(); i++) {
                v[i] = (float) arr.get(i).asDouble();
            }
            out.add(v);
        }
        log.info("向量化完成 n={} dim={}", out.size(), out.isEmpty() ? 0 : out.get(0).length);
        return out;
    }

    // ------------------------------------------------------------------ internal

    private Envelope post(String url, HttpEntity<?> entity) {
        ResponseEntity<String> resp;
        try {
            resp = aiRestTemplate.postForEntity(url, entity, String.class);
        } catch (ResourceAccessException e) {
            throw AiException.llmFailure("AI 服务不可达或超时：" + e.getMessage());
        } catch (Exception e) {
            throw AiException.llmFailure("调用 AI 服务失败：" + e.getMessage());
        }

        String raw = resp.getBody();
        if (raw == null || raw.isBlank()) {
            throw AiException.llmFailure("AI 服务返回空响应，HTTP " + resp.getStatusCode().value());
        }
        Envelope env = json.read(raw, Envelope.class);
        if (env == null) {
            throw AiException.llmFailure("AI 服务返回无法解析：" + raw.substring(0, Math.min(raw.length(), 200)));
        }
        return env;
    }

    private void ensureOk(Envelope env) {
        if (env.code() == 0) {
            return;
        }
        String msg = env.message() == null ? "AI 服务未知错误" : env.message();
        switch (env.code()) {
            case 2001 -> throw AiException.noTextLayer(msg);
            case 2003 -> throw AiException.schemaViolation(msg);
            // 2004：向量化未配置。不是错误，调用方应该降级而不是判失败
            case 2004 -> throw new AiException(2004, msg, false);
            case 1001 -> throw new AiException(1001, msg, false);
            default -> throw AiException.llmFailure(msg);
        }
    }
}
