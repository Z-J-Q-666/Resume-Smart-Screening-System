package com.resume.screening.cache;

/** Redis 键集中定义（需求 §8：业务:对象:标识，全部带 TTL） */
public final class RedisKeys {

    private RedisKeys() {
    }

    public static String resumeStatus(long resumeId) {
        return "resume:status:" + resumeId;
    }

    public static String resumeLock(long resumeId) {
        return "resume:lock:" + resumeId;
    }

    public static String matchLock(long applicationId) {
        return "match:lock:" + applicationId;
    }

    public static String resumeProfile(long resumeId) {
        return "resume:profile:" + resumeId;
    }

    /**
     * 语义分缓存。同一个 (简历, 岗位) 组合的语义分只取决于 (profile, 岗位要求, prompt 版本)，
     * temperature=0 下可以复用——同一份简历投多个岗位、或重复触发打分，都不该每次都花钱。
     * prompt 版本不放进 key，而是存进 value 里比对：版本一换就当作未命中并覆盖，旧键不会堆积。
     */
    public static String semanticScore(long resumeId, long jobId) {
        return "match:semantic:" + resumeId + ":" + jobId;
    }

    public static String board(long jobId) {
        return "board:" + jobId;
    }

    public static String uploadRateLimit(String userId) {
        return "ratelimit:upload:" + userId;
    }

    /** Agent 查询限流：分钟级防连点，日级防通宵刷 */
    public static String agentRatePerMinute(String userId) {
        return "ratelimit:agent:min:" + userId;
    }

    public static String agentRatePerDay(String userId) {
        return "ratelimit:agent:day:" + userId;
    }

    /**
     * 短期对话记忆。
     *
     * <p>只存问答文本，不存工具调用细节——tool 结果动辄几千 token，
     * 存三轮就爆上下文，而真正需要的数据下一轮重查即可。
     */
    public static String agentConversation(String conversationId) {
        return "agent:conv:" + conversationId;
    }
}
