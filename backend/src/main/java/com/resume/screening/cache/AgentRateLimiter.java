package com.resume.screening.cache;

import com.resume.screening.common.BizException;
import com.resume.screening.common.ErrorCode;
import com.resume.screening.config.AppProperties;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * Agent 查询限流。
 *
 * <p>为什么这个接口必须限：一次问句会触发 3-5 轮 LLM 调用，每轮上千 token。
 * 上传接口一次只花一次抽取的钱，而这里一次能花掉五次。不限流就是个成本黑洞。
 *
 * <p>两层限制：
 * <ul>
 *   <li>分钟级 —— 防手抖连点、防脚本循环刷</li>
 *   <li>日级 —— 防通宵跑，这是真正的成本闸门</li>
 * </ul>
 *
 * <p>Redis 不可用时放行（与上传限流一致）：限流不是业务正确性的一部分，
 * 宁可少一层保护，也不能因为 Redis 挂了就让功能不可用。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AgentRateLimiter {

    private final StringRedisTemplate redis;
    private final AppProperties props;

    public void check(String userId) {
        AppProperties.RateLimit cfg = props.getRateLimit();
        hit(RedisKeys.agentRatePerMinute(userId), cfg.getAgentPerMinute(),
                Duration.ofMinutes(1), "每分钟", "次查询");
        hit(RedisKeys.agentRatePerDay(userId), cfg.getAgentPerDay(),
                Duration.ofDays(1), "每天", "次查询");
    }

    private void hit(String key, int limit, Duration window, String label, String unit) {
        try {
            Long n = redis.opsForValue().increment(key);
            if (n != null && n == 1L) {
                // 第一次设置过期。放在 INCR 之后、判断之前，
                // 避免"设置了过期但 INCR 失败"导致 key 永不过期
                redis.expire(key, window);
            }
            if (n != null && n > limit) {
                throw new BizException(ErrorCode.TOO_MANY_REQUESTS,
                        "自然语言查询过于频繁，" + label + "最多 " + limit + " " + unit);
            }
        } catch (BizException e) {
            throw e;
        } catch (Exception e) {
            log.warn("Agent 限流检查失败（放行） key={} err={}", key, e.getMessage());
        }
    }
}
