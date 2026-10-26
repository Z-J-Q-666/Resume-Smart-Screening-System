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

    public static String board(long jobId) {
        return "board:" + jobId;
    }

    public static String uploadRateLimit(String userId) {
        return "ratelimit:upload:" + userId;
    }
}
