package com.resume.screening.agent;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.common.Json;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 工具注册表。
 *
 * <p>工具执行失败**不抛异常**，而是返回 {"error": "..."} 让模型自己看。
 * 这是刻意的：Agent 的价值之一是"工具挂了也能给出下一步怎么办"，
 * 一旦抛异常整轮对话就断了。
 */
@Slf4j
@Component
public class ToolRegistry {

    private final Map<String, AgentTool> tools;
    private final Json json;

    public ToolRegistry(List<AgentTool> toolList, Json json) {
        this.json = json;
        this.tools = toolList.stream().collect(Collectors.toMap(
                AgentTool::name, Function.identity(), (a, b) -> a, LinkedHashMap::new));
        log.info("Agent 工具注册 {} 个：{}", tools.size(), tools.keySet());
    }

    /** 给模型的工具清单 */
    public List<Map<String, Object>> specs() {
        return tools.values().stream()
                .map(t -> {
                    Map<String, Object> m = new LinkedHashMap<>();
                    m.put("name", t.name());
                    m.put("description", t.description());
                    m.put("parameters", t.parameters());
                    return m;
                })
                .toList();
    }

    public String execute(String name, JsonNode args) {
        AgentTool tool = tools.get(name);
        if (tool == null) {
            log.warn("模型调用了不存在的工具 name={}", name);
            return json.write(Map.of("error", "未知工具：" + name + "，可用工具：" + tools.keySet()));
        }
        long t0 = System.currentTimeMillis();
        try {
            String result = tool.execute(args == null ? json.mapper().createObjectNode() : args);
            log.info("工具执行完成 name={} costMs={} resultLen={}",
                    name, System.currentTimeMillis() - t0, result == null ? 0 : result.length());
            return result == null ? "{}" : result;
        } catch (Exception e) {
            log.warn("工具执行失败 name={} err={}", name, e.getMessage());
            return json.write(Map.of("error", "工具执行失败：" + e.getMessage()));
        }
    }

    public int size() {
        return tools.size();
    }
}
