package com.resume.screening.match;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * 技能归一化（需求 FR-AI-03 / 附录 A）。
 * 映射表与 Python 侧 ai-service/resources/skill_alias.json 同源，两边口径必须一致，
 * 否则"命中技能数"会对不上。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillNormalizer {

    private final ObjectMapper mapper;

    private Map<String, String> alias = Map.of();
    private final Set<String> canonicalLower = new HashSet<>();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("skill_alias.json").getInputStream()) {
            Map<String, String> raw = mapper.readValue(in, new TypeReference<Map<String, String>>() {
            });
            Map<String, String> normalized = new HashMap<>();
            raw.forEach((k, v) -> normalized.put(k.trim().toLowerCase(Locale.ROOT), v));
            this.alias = normalized;
            this.canonicalLower.clear();
            normalized.values().forEach(v -> canonicalLower.add(v.toLowerCase(Locale.ROOT)));
            log.info("技能映射表加载完成 entries={}", alias.size());
        } catch (Exception e) {
            log.error("技能映射表加载失败，将退化为大小写不敏感比较", e);
        }
    }

    /** 归一化为标准写法；表里没有的原样返回（去首尾空白）。 */
    public String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        if (s.isEmpty()) {
            return null;
        }
        String k = key(s);
        String hit = alias.get(k);
        if (hit != null) {
            return hit;
        }
        return s;
    }

    /** 比较用的键：小写 + 空白折叠 */
    public String key(String raw) {
        if (raw == null) {
            return "";
        }
        return raw.trim().toLowerCase(Locale.ROOT).replaceAll("\\s+", " ");
    }
}
