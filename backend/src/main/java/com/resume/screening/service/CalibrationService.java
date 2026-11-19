package com.resume.screening.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.ai.AiClient;
import com.resume.screening.common.Json;
import com.resume.screening.domain.Resume;
import com.resume.screening.dto.HiredCandidate;
import com.resume.screening.mapper.HiringDecisionMapper;
import com.resume.screening.mapper.ResumeMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

/**
 * 语义分校准：从"同岗位被录取过的人"里挑几个最像当前候选人的，作为打分锚点。
 *
 * <p>解决的是根因问题。之前量出来语义分整体偏低 26.5 分、跨方向时 σ 只有 2.0——
 * 模型不知道这个岗位的门槛在哪，只能靠通用直觉猜。给它几个已录用的参照，尺度才有落点。
 *
 * <p>两条路径：有向量化就按语义相似度排，没有就退回技能/年限/学历的本地相似度。
 * 后者粗糙但零依赖，保证功能永远可用。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class CalibrationService {

    private static final int MAX_EXAMPLES = 3;
    private static final int DIGEST_MAX = 110;

    private final HiringDecisionMapper decisionMapper;
    private final ResumeMapper resumeMapper;
    private final EmbeddingService embeddingService;
    private final Json json;

    /**
     * 挑锚点。返回空列表表示"没有参照"——Python 侧会跳过锚点段落，
     * 退回原来的打分逻辑，不会因为缺参照而失败。
     */
    public List<AiClient.Example> pickExamples(long jobId, long selfResumeId, JsonNode selfProfile) {
        if (selfProfile == null) {
            return List.of();
        }

        List<HiredCandidate> others = decisionMapper.selectHiredWithScore(jobId).stream()
                .filter(h -> !Objects.equals(h.getResumeId(), selfResumeId))
                .toList();
        if (others.isEmpty()) {
            // 冷启动：这个岗位还没标记过录用，没有参照可用
            return List.of();
        }

        List<Long> ids = others.stream().map(HiredCandidate::getResumeId).toList();
        Map<Long, Resume> resumes = resumeMapper.selectBatchIds(ids).stream()
                .collect(Collectors.toMap(Resume::getId, Function.identity()));

        List<HiredCandidate> picked = rankByVector(selfResumeId, selfProfile, others, resumes);
        if (picked.isEmpty()) {
            picked = rankByLocalSimilarity(selfProfile, others, resumes);
        }

        List<AiClient.Example> examples = toExamples(picked, resumes);
        if (!examples.isEmpty()) {
            log.info("语义分校准 jobId={} resumeId={} 参照数={}", jobId, selfResumeId, examples.size());
        }
        return examples;
    }

    // ---------------------------------------------------------------- 向量路径

    private List<HiredCandidate> rankByVector(long selfResumeId, JsonNode selfProfile,
                                              List<HiredCandidate> others, Map<Long, Resume> resumes) {
        Optional<float[]> selfVec = embeddingService.getOrCreate(selfResumeId, selfProfile);
        if (selfVec.isEmpty()) {
            return List.of();
        }

        List<Scored> scored = new ArrayList<>();
        for (HiredCandidate c : others) {
            JsonNode p = profileOf(resumes.get(c.getResumeId()));
            if (p == null) {
                continue;
            }
            Optional<float[]> v = embeddingService.getOrCreate(c.getResumeId(), p);
            if (v.isEmpty()) {
                continue;
            }
            scored.add(new Scored(c, cosine(selfVec.get(), v.get())));
        }
        if (scored.isEmpty()) {
            return List.of();
        }

        scored.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return scored.stream().limit(MAX_EXAMPLES).map(Scored::candidate).toList();
    }

    private record Scored(HiredCandidate candidate, double similarity) {
    }

    private static double cosine(float[] a, float[] b) {
        if (a == null || b == null || a.length != b.length) {
            return 0;
        }
        double dot = 0;
        double na = 0;
        double nb = 0;
        for (int i = 0; i < a.length; i++) {
            dot += a[i] * b[i];
            na += a[i] * a[i];
            nb += b[i] * b[i];
        }
        return (na == 0 || nb == 0) ? 0 : dot / (Math.sqrt(na) * Math.sqrt(nb));
    }

    // ---------------------------------------------------------------- 降级路径

    /**
     * 没有向量化时的相似度：技能重合度 0.6 + 年限接近度 0.25 + 学历一致 0.15。
     *
     * <p>权重是拍的，但方向对——筛简历时"技术栈交集"就是最硬的信号，
     * 年限其次，学历在现代技术岗里最弱。
     */
    private List<HiredCandidate> rankByLocalSimilarity(JsonNode selfProfile,
                                                       List<HiredCandidate> others,
                                                       Map<Long, Resume> resumes) {
        List<Scored> scored = new ArrayList<>();
        for (HiredCandidate c : others) {
            JsonNode p = profileOf(resumes.get(c.getResumeId()));
            if (p == null) {
                continue;
            }
            scored.add(new Scored(c, localSimilarity(selfProfile, p)));
        }
        if (scored.isEmpty()) {
            return List.of();
        }
        scored.sort((a, b) -> Double.compare(b.similarity(), a.similarity()));
        return scored.stream().limit(MAX_EXAMPLES).map(Scored::candidate).toList();
    }

    private static double localSimilarity(JsonNode a, JsonNode b) {
        double skill = jaccard(skillsOf(a), skillsOf(b));
        double exp = expProximity(yearsOf(a), yearsOf(b));
        double edu = eduMatch(degreeOf(a), degreeOf(b));
        return 0.6 * skill + 0.25 * exp + 0.15 * edu;
    }

    private static double jaccard(Set<String> a, Set<String> b) {
        if (a.isEmpty() && b.isEmpty()) {
            return 0;
        }
        Set<String> inter = new LinkedHashSet<>(a);
        inter.retainAll(b);
        Set<String> union = new LinkedHashSet<>(a);
        union.addAll(b);
        return union.isEmpty() ? 0 : (double) inter.size() / union.size();
    }

    private static double expProximity(Double a, Double b) {
        if (a == null || b == null) {
            return 0;
        }
        double gap = Math.abs(a - b);
        return Math.max(0, 1 - gap / Math.max(5, Math.max(a, b)));
    }

    private static double eduMatch(String a, String b) {
        if (a == null || b == null) {
            return 0;
        }
        if (a.equals(b)) {
            return 1;
        }
        List<String> ladder = List.of("大专", "本科", "硕士", "博士");
        int i = ladder.indexOf(a);
        int j = ladder.indexOf(b);
        return (i >= 0 && j >= 0 && Math.abs(i - j) == 1) ? 0.5 : 0;
    }

    private static Set<String> skillsOf(JsonNode p) {
        Set<String> out = new LinkedHashSet<>();
        if (p.path("skills").isArray()) {
            p.get("skills").forEach(n -> out.add(n.asText("").toLowerCase()));
        }
        out.remove("");
        return out;
    }

    private static Double yearsOf(JsonNode p) {
        return p.hasNonNull("total_years") ? p.get("total_years").asDouble() : null;
    }

    private static String degreeOf(JsonNode p) {
        String d = p.path("education").path("degree").asText("");
        return d.isEmpty() ? null : d;
    }

    // ---------------------------------------------------------------- 组装

    private List<AiClient.Example> toExamples(List<HiredCandidate> picked, Map<Long, Resume> resumes) {
        List<AiClient.Example> out = new ArrayList<>(picked.size());
        for (HiredCandidate c : picked) {
            JsonNode p = profileOf(resumes.get(c.getResumeId()));
            String digest = p == null ? "（画像缺失）" : truncate(EmbeddingService.profileText(p), DIGEST_MAX);
            int score = c.getSemanticScore() == null ? 80 : c.getSemanticScore().intValue();
            out.add(new AiClient.Example(digest, score));
        }
        return out;
    }

    private JsonNode profileOf(Resume r) {
        return (r == null || r.getParsedJson() == null) ? null : json.read(r.getParsedJson());
    }

    private static String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max) + "…";
    }
}
