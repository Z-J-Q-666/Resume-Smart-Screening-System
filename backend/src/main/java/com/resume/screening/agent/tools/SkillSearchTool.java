package com.resume.screening.agent.tools;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.agent.AgentTool;
import com.resume.screening.common.Json;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 技能别名查询。
 *
 * <p>用途：用户说"会点 springboot"，字面检索搜不到 "Spring Boot"。
 * 让模型先查一次标准写法，再去查人，命中率会高很多。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class SkillSearchTool implements AgentTool {

    private final Json json;
    private Map<String, String> alias = Map.of();

    @PostConstruct
    void load() {
        try (InputStream in = new ClassPathResource("skill_alias.json").getInputStream()) {
            alias = json.mapper().readValue(in, new TypeReference<Map<String, String>>() {
            });
            log.info("技能别名词典加载完成 entries={}", alias.size());
        } catch (Exception e) {
            log.warn("技能别名词典加载失败，工具将返回空结果：{}", e.getMessage());
        }
    }

    @Override
    public String name() {
        return "search_skills";
    }

    @Override
    public String description() {
        return "把用户口语化的技术名词（如 springboot、k8s、js）映射成系统里的标准写法（Spring Boot、Kubernetes、JavaScript）。"
                + "在按技能筛选候选人之前，先用它确认标准写法，能显著提高检索命中率。";
    }

    @Override
    public Map<String, Object> parameters() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("keyword", Map.of("type", "string", "description", "技能关键词，可以是缩写或非标准写法"));
        return Map.of("type", "object", "properties", props, "required", List.of("keyword"));
    }

    @Override
    public String execute(JsonNode args) {
        String keyword = args.path("keyword").asText("").trim().toLowerCase();
        if (keyword.isEmpty()) {
            return json.write(Map.of("error", "缺少 keyword"));
        }

        Set<String> matches = new LinkedHashSet<>();
        for (Map.Entry<String, String> e : alias.entrySet()) {
            if (e.getKey().contains(keyword) || e.getValue().toLowerCase().contains(keyword)) {
                matches.add(e.getValue());
            }
            if (matches.size() >= 12) {
                break;
            }
        }

        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("keyword", keyword);
        payload.put("canonical", new ArrayList<>(matches));
        if (matches.isEmpty()) {
            payload.put("hint", "词典里没有匹配项。可以直接用原词去 search_applications 里试。");
        }
        return json.write(payload);
    }
}
