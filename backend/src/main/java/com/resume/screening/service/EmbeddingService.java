package com.resume.screening.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.ai.AiClient;
import com.resume.screening.common.Json;
import com.resume.screening.domain.ResumeEmbedding;
import com.resume.screening.mapper.ResumeEmbeddingMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * 简历画像的向量化。
 *
 * <p>设计成"能算就算，算不了就返回 empty"：向量化需要额外的 key（DeepSeek 不提供 embedding），
 * 没有也不该让打分功能瘫掉。调用方拿到 empty 就退回本地相似度。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class EmbeddingService {

    /** 模型名缓存时长。换模型是低频操作，10 分钟足够 */
    private static final long MODEL_TTL_MS = 10 * 60 * 1000L;

    private final AiClient aiClient;
    private final ResumeEmbeddingMapper embeddingMapper;
    private final Json json;

    private volatile String modelCache;
    private volatile long modelCheckedAt;

    /** 当前可用的嵌入模型名；null 表示不可用 */
    public String currentModel() {
        long now = System.currentTimeMillis();
        if (modelCache != null && now - modelCheckedAt < MODEL_TTL_MS) {
            return modelCache;
        }
        try {
            AiClient.HealthInfo info = aiClient.health();
            modelCache = info.embeddingAvailable() && !info.embeddingModel().isBlank()
                    ? info.embeddingModel() : null;
        } catch (Exception e) {
            modelCache = null;
        }
        modelCheckedAt = now;
        return modelCache;
    }

    public boolean available() {
        return currentModel() != null;
    }

    /**
     * 取向量：库里有且 profile 没变就直接用，否则现算并落库。
     *
     * <p>用 sourceHash 而不是每次重算：一份简历可能被反复打分（换岗位、重试、对账补投），
     * 每次都调 embedding 接口既慢又费钱。
     */
    public Optional<float[]> getOrCreate(long resumeId, JsonNode profile) {
        String model = currentModel();
        if (model == null || profile == null) {
            return Optional.empty();
        }

        String hash = ResumeService.sha256(profile.toString().getBytes(StandardCharsets.UTF_8));
        ResumeEmbedding existing = embeddingMapper.selectOne(new LambdaQueryWrapper<ResumeEmbedding>()
                .eq(ResumeEmbedding::getResumeId, resumeId)
                .eq(ResumeEmbedding::getModel, model));

        if (existing != null && hash.equals(existing.getSourceHash())) {
            return parseVector(existing.getVector());
        }

        try {
            List<float[]> vectors = aiClient.embed(List.of(profileText(profile)));
            if (vectors.isEmpty() || vectors.get(0).length == 0) {
                return Optional.empty();
            }
            float[] vector = vectors.get(0);
            save(resumeId, model, vector, hash);
            return Optional.of(vector);
        } catch (Exception e) {
            // 2004（未配置）、网络抖动、额度不足都走这里，静默降级
            log.warn("向量化失败，本次降级到本地相似度 resumeId={} err={}", resumeId, e.getMessage());
            return Optional.empty();
        }
    }

    /**
     * 把结构化 profile 拍成一段人话。
     *
     * <p>为什么不直接嵌 JSON：embedding 模型对自然语言更敏感，
     * 一堆花括号和引号会稀释语义。这里只保留"会影响判断"的字段。
     */
    public static String profileText(JsonNode p) {
        StringBuilder sb = new StringBuilder();

        String degree = p.path("education").path("degree").asText("");
        if (!degree.isEmpty()) {
            sb.append(degree).append("学历。");
        }
        if (p.hasNonNull("total_years")) {
            sb.append(p.get("total_years").asDouble()).append("年工作经验。");
        }
        if (p.path("skills").isArray() && p.get("skills").size() > 0) {
            List<String> skills = new ArrayList<>();
            p.get("skills").forEach(n -> skills.add(n.asText()));
            sb.append("技能：").append(String.join("、", skills)).append("。");
        }
        if (p.path("experiences").isArray()) {
            List<String> items = new ArrayList<>();
            for (JsonNode e : p.get("experiences")) {
                if (items.size() >= 3) {
                    break;
                }
                String title = e.path("title").asText("");
                String company = e.path("company").asText("");
                if (!title.isEmpty() || !company.isEmpty()) {
                    items.add(title + "@" + company);
                }
            }
            if (!items.isEmpty()) {
                sb.append("经历：").append(String.join("；", items)).append("。");
            }
        }
        String summary = p.path("summary").asText("");
        if (!summary.isEmpty()) {
            sb.append(summary);
        }
        return sb.toString();
    }

    private void save(long resumeId, String model, float[] vector, String hash) {
        ResumeEmbedding row = new ResumeEmbedding();
        row.setResumeId(resumeId);
        row.setModel(model);
        row.setDim(vector.length);
        row.setVector(json.write(vector));
        row.setSourceHash(hash);
        try {
            embeddingMapper.insert(row);
        } catch (DuplicateKeyException e) {
            // 并发打分时两个线程同时算同一份简历，唯一键兜住，不算错误
            log.debug("向量已存在，跳过写入 resumeId={}", resumeId);
        }
    }

    private Optional<float[]> parseVector(String jsonText) {
        JsonNode arr = json.read(jsonText);
        if (arr == null || !arr.isArray() || arr.size() == 0) {
            return Optional.empty();
        }
        float[] v = new float[arr.size()];
        for (int i = 0; i < arr.size(); i++) {
            v[i] = (float) arr.get(i).asDouble();
        }
        return Optional.of(v);
    }
}
