package com.resume.screening.match;

import com.fasterxml.jackson.databind.JsonNode;
import com.resume.screening.domain.Job;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 规则分（需求 §9.3）：学历 40 + 年限 30 + 必备技能覆盖率 30，满分 100。
 * 全是确定性计算，同样的输入永远同样的输出，可复现、可解释、零成本。
 */
@Component
@RequiredArgsConstructor
public class ScoreCalculator {

    private static final int EDUCATION_FULL = 40;
    private static final int EDUCATION_ONE_DOWN = 15;
    private static final int EXP_FULL = 30;
    private static final int EXP_ONE_SHORT = 15;
    private static final int SKILL_FULL = 30;

    private static final List<String> DEGREE_LADDER = List.of("大专", "本科", "硕士", "博士");

    private final SkillNormalizer normalizer;
    private final com.resume.screening.common.Json json;

    public record Breakdown(int educationScore,
                            int expScore,
                            int skillScore,
                            int ruleScore,
                            List<String> hitSkills,
                            List<String> missSkills) {
    }

    public Breakdown calculate(Job job, JsonNode profile) {
        int edu = educationScore(job.getEducationReq(), degreeOf(profile));
        int exp = expScore(job.getExpYearsReq(), yearsOf(profile));

        List<String> must = readStringList(job.getMustSkills());
        Set<String> candidateKeys = skillKeys(profile);

        List<String> hit = new ArrayList<>();
        List<String> miss = new ArrayList<>();
        Set<String> hitKeys = new LinkedHashSet<>();
        for (String raw : must) {
            String canonical = normalizer.normalize(raw);
            if (canonical == null) {
                continue;
            }
            String k = normalizer.key(canonical);
            if (candidateKeys.contains(k) || hitKeys.contains(k)) {
                if (hitKeys.add(k)) {
                    hit.add(canonical);
                }
            } else {
                miss.add(canonical);
            }
        }

        int total = hit.size() + miss.size();
        int skill = total == 0 ? SKILL_FULL : (int) Math.round(hit.size() * (double) SKILL_FULL / total);

        return new Breakdown(edu, exp, skill, edu + exp + skill, hit, miss);
    }

    // ---------------------------------------------------------------- 分项

    int educationScore(String requirement, String candidateDegree) {
        if (requirement == null || requirement.isBlank() || "不限".equals(requirement)) {
            return EDUCATION_FULL;
        }
        int need = degreeRank(requirement);
        if (need == 0) {
            // 无法识别的学历要求按"不限"处理，不误伤候选人
            return EDUCATION_FULL;
        }
        int got = degreeRank(candidateDegree);
        if (got >= need) {
            return EDUCATION_FULL;
        }
        if (got == need - 1) {
            return EDUCATION_ONE_DOWN;
        }
        return 0;
    }

    int expScore(Integer requirement, Double candidateYears) {
        int req = requirement == null ? 0 : requirement;
        if (req <= 0) {
            return EXP_FULL;
        }
        if (candidateYears == null) {
            // 简历没写明年限，无法确认达标，按不满足计
            return 0;
        }
        double gap = req - candidateYears;
        if (gap <= 0) {
            return EXP_FULL;
        }
        if (gap < 2) {
            return EXP_ONE_SHORT;
        }
        return 0;
    }

    static int degreeRank(String degree) {
        if (degree == null) {
            return 0;
        }
        int idx = DEGREE_LADDER.indexOf(degree.trim());
        return idx < 0 ? 0 : idx + 1;
    }

    // ---------------------------------------------------------------- Profile

    public static String degreeOf(JsonNode profile) {
        if (profile == null) {
            return null;
        }
        JsonNode n = profile.path("education").path("degree");
        return n.isMissingNode() || n.isNull() ? null : n.asText(null);
    }

    public static Double yearsOf(JsonNode profile) {
        if (profile == null) {
            return null;
        }
        JsonNode n = profile.path("total_years");
        if (n.isMissingNode() || n.isNull()) {
            return null;
        }
        return n.isNumber() ? n.asDouble() : null;
    }

    private Set<String> skillKeys(JsonNode profile) {
        Set<String> keys = new LinkedHashSet<>();
        if (profile == null) {
            return keys;
        }
        JsonNode skills = profile.path("skills");
        if (!skills.isArray()) {
            return keys;
        }
        for (JsonNode s : skills) {
            String canonical = normalizer.normalize(s.asText(""));
            if (canonical != null) {
                keys.add(normalizer.key(canonical));
            }
        }
        return keys;
    }

    private List<String> readStringList(String jsonArray) {
        return json.toStringList(jsonArray);
    }
}
