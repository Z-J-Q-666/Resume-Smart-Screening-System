"""匹配链：Profile + 岗位 → 语义分 + 推荐理由。"""

import json
import time

from .llm import structured_call
from .schemas import MatchRequest, MatchResult

# prompt 版本：改动 _SYSTEM 或 _user_prompt 时必须递增。
# Java 侧拿它做语义分缓存的 key 后缀——prompt 一改，旧缓存自动失效，
# 不会出现"新 prompt 配旧分数"的脏数据。
PROMPT_VERSION = "v1"

_SYSTEM = """你是一位资深技术面试官，负责判断候选人经历与岗位要求的契合程度，输出 JSON。

评分口径（必须遵守）：

1. 只评估"经历与岗位要求的契合程度"：做过什么方向的系统、用什么技术栈、遇到过什么量级的问题。
2. 学历是否达标、工作年限是否够，已经由系统按规则单独算分，你**不要**因为学历或年限再扣分。
   年限只用来判断候选人处于哪个经验层次，用来校准你的评分尺度，不作为扣分项。
3. 判断权重：核心业务方向匹配 > 主力技术栈匹配 > 项目复杂度与深度 > 加分技能覆盖。
4. 分数是整数，0-100，请拉开区分度：
   - 85-100：核心方向高度一致，主力技术栈几乎全中，且有同量级项目经验
   - 70-84：方向一致，主力技术栈大部分匹配，个别环节需要适应
   - 50-69：方向部分相关，技术栈有交集但需要较长上手期
   - 30-49：方向不同，仅有通用工程能力可迁移
   - 0-29：基本不相关
   不要因为"看起来还行"就给 70 分以上的中庸分，必须有明确判断。
5. reason 不超过 120 字，中文，直接说结论：匹配在哪里、差在哪里。禁止空话套话，
   禁止出现"建议面试""综合评估"这类无信息量表述。
6. 输出必须是合法 JSON：{"semantic_score": <整数>, "reason": "<字符串>"}，不要任何解释文字。
"""


def _profile_digest(req: MatchRequest) -> dict:
    p = req.profile
    return {
        "summary": p.summary,
        "total_years": p.total_years,
        "degree": p.education.degree if p.education else None,
        "major": p.education.major if p.education else None,
        "skills": p.skills,
        "experiences": [
            {"company": e.company, "title": e.title, "period": e.period, "summary": e.summary}
            for e in p.experiences
        ],
    }


def _user_prompt(req: MatchRequest, schema_json: dict) -> str:
    job = {
        "title": req.job_title,
        "must_skills": req.must_skills,
        "plus_skills": req.plus_skills,
        "jd_text": (req.jd_text or "")[:3000],
    }
    return f"""【岗位】
{json.dumps(job, ensure_ascii=False, indent=2)}

【候选人】
{json.dumps(_profile_digest(req), ensure_ascii=False, indent=2)}

【输出 JSON Schema】
{json.dumps(schema_json, ensure_ascii=False, indent=2)}

请给出语义分与推荐理由，输出 JSON："""


def match_profile(req: MatchRequest) -> tuple[MatchResult, dict[str, int], int]:
    started = time.time()
    result, tokens = structured_call(
        _SYSTEM,
        _user_prompt(req, MatchResult.model_json_schema()),
        MatchResult,
    )
    cost_ms = int((time.time() - started) * 1000)
    return result, tokens, cost_ms
