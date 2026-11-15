package com.resume.screening.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Data
@Component
@ConfigurationProperties(prefix = "app")
public class AppProperties {

    /** 文件存储根目录 */
    private String uploadDir = "./uploads";

    private Ai ai = new Ai();
    private Mq mq = new Mq();
    private RateLimit rateLimit = new RateLimit();
    private Score score = new Score();

    @Data
    public static class Ai {
        private String baseUrl = "http://127.0.0.1:8000";
        private int connectTimeoutMs = 3000;
        private int readTimeoutMs = 90_000;

        /**
         * 必须与 ai-service/app/match_chain.py 的 PROMPT_VERSION 一致。
         * 不一致时语义分缓存会一直未命中（只是浪费一次调用，不会算错分），
         * 所以启动时比对 /health 并打 WARN，让人能发现。
         */
        private String matchPromptVersion = "v1";

        /** 成本估算单价（元 / 百万 token）。按官方价填，改价只改这两个值，别散落在代码里。 */
        private double pricePromptPerMillion = 2.0;
        private double priceCompletionPerMillion = 8.0;
    }

    @Data
    public static class Mq {
        private String namesrvAddr = "127.0.0.1:9876";
        private String producerGroup = "resume-screening-producer";
        private String extractTopic = "resume-extract";
        private String extractConsumerGroup = "resume-extract-consumer-group";
        private String matchTopic = "match-score";
        private String matchConsumerGroup = "match-score-consumer-group";
        private int consumeThreads = 2;
        private int maxReconsumeTimes = 3;
        private boolean enabled = true;
    }

    @Data
    public static class RateLimit {
        private int uploadPerMinute = 20;
    }

    @Data
    public static class Score {
        private double ruleWeight = 0.6;
        private double semanticWeight = 0.4;
    }
}
