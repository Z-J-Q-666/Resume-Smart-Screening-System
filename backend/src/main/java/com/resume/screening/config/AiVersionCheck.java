package com.resume.screening.config;

import com.resume.screening.ai.AiClient;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

import java.util.Objects;

/**
 * 启动时比对 Java 与 Python 的 prompt 版本。
 *
 * <p>为什么值得单开一个类：版本不一致不会报错，只会让语义分缓存永远未命中——
 * 表现为"缓存明明写了却不生效"，极难排查。用一次启动告警换掉一个静默故障。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class AiVersionCheck implements ApplicationRunner {

    private final AiClient aiClient;
    private final AppProperties props;

    @Override
    public void run(ApplicationArguments args) {
        String local = props.getAi().getMatchPromptVersion();
        AiClient.HealthInfo info;
        try {
            info = aiClient.health();
        } catch (Exception e) {
            // AI 服务通常比后端起得晚，这里只记一条 info，不升级成错误
            log.info("AI 服务未就绪，跳过 prompt 版本校验（本地 match={}）：{}", local, e.getMessage());
            return;
        }
        if (!Objects.equals(local, info.matchPromptVersion())) {
            log.warn("prompt 版本不一致：本地 match={}，AI 服务={}。语义分缓存将持续未命中，"
                            + "请对齐 application.yml 的 app.ai.match-prompt-version 与 match_chain.py 的 PROMPT_VERSION",
                    local, info.matchPromptVersion());
        } else {
            log.info("prompt 版本一致 match={} extract={} model={}",
                    info.matchPromptVersion(), info.extractPromptVersion(), info.model());
        }
    }
}
