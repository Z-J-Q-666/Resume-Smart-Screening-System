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
}
