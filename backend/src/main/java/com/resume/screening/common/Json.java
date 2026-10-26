package com.resume.screening.common;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.List;

/**
 * JSON 列统一走这里：MySQL 的 JSON 列在实体里按 String 存取，
 * 好处是读写都是明文、不依赖 MyBatis 的类型处理器，排查时直接看库就懂。
 */
@Component
@RequiredArgsConstructor
public class Json {

    private static final TypeReference<List<String>> STRING_LIST = new TypeReference<>() {
    };

    private final ObjectMapper mapper;

    public String write(Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (Exception e) {
            throw new IllegalStateException("序列化失败", e);
        }
    }

    public JsonNode read(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readTree(json);
        } catch (Exception e) {
            return null;
        }
    }

    public <T> T read(String json, Class<T> type) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, type);
        } catch (Exception e) {
            throw new IllegalStateException("反序列化失败", e);
        }
    }

    public List<String> toStringList(String json) {
        if (json == null || json.isBlank()) {
            return List.of();
        }
        try {
            List<String> v = mapper.readValue(json, STRING_LIST);
            return v == null ? List.of() : v;
        } catch (Exception e) {
            return List.of();
        }
    }

    public ObjectMapper mapper() {
        return mapper;
    }
}
