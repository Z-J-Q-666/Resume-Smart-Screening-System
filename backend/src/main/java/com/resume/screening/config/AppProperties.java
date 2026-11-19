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

        /**
         * Agent 查询限流。
         *
         * <p>一次问句会触发 3-5 轮 LLM 调用，比上传贵一个量级，所以额度小得多；
         * 日上限是真正的成本闸门——分钟级只能防连点，防不住通宵跑脚本。
         */
        private int agentPerMinute = 10;
        private int agentPerDay = 200;
    }

    @Data
    public static class Score {
        private double ruleWeight = 0.6;
        private double semanticWeight = 0.4;

        /**
         * 语义分分组阈值。
         *
         * <p>榜单先按分组排、组内再按综合分排——因为语义分回答的是"方向对不对"，
         * 不是"强多少"。跨方向时它会集体挤在低分区（实测四个候选人 38/38/42/42），
         * 继续拿它当排序主键等于没排。
         *
         * <p>SQL 的 CASE 判断从这里取值，不要在两处硬编码。
         */
        private int matchHighThreshold = 70;
        private int matchPartialThreshold = 50;
    }
}
