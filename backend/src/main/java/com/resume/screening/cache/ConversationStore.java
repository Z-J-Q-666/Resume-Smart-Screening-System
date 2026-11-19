package com.resume.screening.cache;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.common.Json;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Agent 的短期记忆：同一次对话里能接着问"那第二个人呢"。
 *
 * <p>只存 user/assistant 两种角色，**不存工具调用与结果**：
 * tool 消息动辄几千 token，存三轮就顶满上下文，而需要的数据下一轮重查即可——
 * 查一次的成本远低于每轮背着历史工具结果跑。
 *
 * <p>Redis 不可用时退化成"每次都是新对话"：记忆是体验优化，不该成为可用性的一部分。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ConversationStore {

    /** 30 分钟没有新消息就清掉，避免旧上下文污染新问题 */
    private static final Duration TTL = Duration.ofMinutes(30);
    /** 最多保留 3 轮问答。再多，历史会挤掉当前问题的注意力 */
    private static final int MAX_TURNS = 3;
    /** 单条答案进历史时截断。模型不需要上一轮的完整措辞，只需要知道结论 */
    private static final int ANSWER_MAX = 200;

    private final StringRedisTemplate redis;
    private final Json json;

    /**
     * 进程内兜底。
     *
     * <p>Redis 没起或抖动时，至少单机能记住上下文——记忆属于体验优化，
     * 但它一旦"有时有有时没有"，用起来比完全没有更难受。
     * 多实例部署时这层会不一致，那时必须依赖 Redis（届时把它关掉即可）。
     */
    private final Map<String, LocalEntry> local = new ConcurrentHashMap<>();

    private record LocalEntry(List<Map<String, String>> history, long expireAt) {
    }

    /** 进程内最多留多少个会话，防止长期运行把内存吃光 */
    private static final int LOCAL_MAX = 1000;

    public String newId() {
        return UUID.randomUUID().toString().replace("-", "").substring(0, 12);
    }

    /** 读历史。任何异常都当作"没有历史"，不影响本次回答 */
    public List<Map<String, String>> load(String conversationId) {
        if (conversationId == null || conversationId.isBlank()) {
            return List.of();
        }

        LocalEntry cached = local.get(conversationId);
        if (cached != null && cached.expireAt() > System.currentTimeMillis()) {
            return cached.history();
        }
        local.remove(conversationId);

        try {
            String raw = redis.opsForValue().get(RedisKeys.agentConversation(conversationId));
            if (raw == null || raw.isBlank()) {
                return List.of();
            }
            JsonNode arr = json.read(raw);
            List<Map<String, String>> out = new ArrayList<>();
            if (arr != null && arr.isArray()) {
                for (JsonNode n : arr) {
                    Map<String, String> m = new LinkedHashMap<>();
                    m.put("role", n.path("role").asText("user"));
                    m.put("content", n.path("content").asText(""));
                    out.add(m);
                }
            }
            if (!out.isEmpty()) {
                local.put(conversationId, new LocalEntry(out, System.currentTimeMillis() + TTL.toMillis()));
            }
            return out;
        } catch (Exception e) {
            log.warn("读取对话记忆失败（当作新会话） convId={} err={}", conversationId, e.getMessage());
            return List.of();
        }
    }

    /** 追加一轮问答并刷新 TTL */
    public void append(String conversationId, String question, String answer) {
        if (conversationId == null || conversationId.isBlank()) {
            return;
        }

        List<Map<String, String>> history = new ArrayList<>(load(conversationId));
        history.add(Map.of("role", "user", "content", question));
        history.add(Map.of("role", "assistant", "content", truncate(answer, ANSWER_MAX)));

        int max = MAX_TURNS * 2;
        if (history.size() > max) {
            history = new ArrayList<>(history.subList(history.size() - max, history.size()));
        }

        // 先写内存，保证就算 Redis 挂了本轮记忆也还在
        evictIfNeeded();
        local.put(conversationId, new LocalEntry(history, System.currentTimeMillis() + TTL.toMillis()));

        try {
            redis.opsForValue().set(RedisKeys.agentConversation(conversationId), json.write(history), TTL);
        } catch (Exception e) {
            log.warn("写入 Redis 记忆失败（仅有进程内记忆） convId={} err={}",
                    conversationId, e.getMessage());
        }
    }

    private void evictIfNeeded() {
        if (local.size() <= LOCAL_MAX) {
            return;
        }
        long now = System.currentTimeMillis();
        local.entrySet().removeIf(e -> e.getValue().expireAt() <= now);
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
