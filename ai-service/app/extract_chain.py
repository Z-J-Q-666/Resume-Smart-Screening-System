"""抽取链：简历纯文本 → ResumeProfile。"""

import json
import time
from datetime import date

from .llm import structured_call
from .schemas import ResumeProfile
from .skill_alias import normalize_skills

# prompt 版本：改动 _SYSTEM 或 _user_prompt 时必须递增。
# 版本号会落进 resume.prompt_version 与 ai_call_log.prompt_version——
# 没有这个锚点，就回答不了"准确率变化是 prompt 改的，还是换了一批简历"。
#   v1 → v2：user prompt 加入【今天日期】，修正"2020.07-至今"被算成 null 的问题。
PROMPT_VERSION = "v2"

_SYSTEM = """你是一位严谨的简历信息抽取引擎。你的唯一任务是从简历文本中抽取结构化信息，输出 JSON。

以下三条是硬约束，违反任意一条即视为错误输出：

1. 简历中未出现的信息一律返回 null，禁止推测、禁止补全。
   学历没写就返回 null，不要因为"看起来像应届生"就填"本科"；
   电话、邮箱没写就返回 null，不要编造。
2. total_years 只统计正式全职工作经历。实习、兼职、校内项目、竞赛、外包短期项目一律不计入。
   某段经历写成"至今 / 现在 / 目前"的，按给定的【今天日期】算到当前月份，不要因此返回 null。
   应届生只有实习没有正式工作，返回 0，不要返回 null。
   只有连起止年月都没有、或存在明显重叠无法判断时，才返回 null。
3. skills 只收录简历文本中明确出现的技术名词（编程语言、框架、中间件、数据库、工具、平台）。
   禁止输出"编程能力强""学习能力好""沟通能力佳"这类描述性短语，禁止输出业务名词如"电商""支付"。
   输出统一使用标准写法：SpringBoot → Spring Boot，mysql → MySQL，rocketmq → RocketMQ，
   js → JavaScript，py → Python，vue3 → Vue，k8s → Kubernetes。

其他要求：
- education.degree 只能取：大专 / 本科 / 硕士 / 博士 / 其他。无法判断时 education 整体返回 null。
- experiences 按时间倒序，只放正式工作经历，每段 summary 不超过 120 字，写清做了什么、用了什么技术、结果如何。
- summary 是一句话人物画像（不超过 200 字），概括候选人的技术方向与经验层次，不要复述简历。
- 输出必须是合法 JSON，字段名与 Schema 完全一致，不要包裹 markdown 代码块，不要任何解释文字。
"""


def _user_prompt(text: str, schema_json: dict) -> str:
    today = date.today().strftime("%Y-%m-%d")
    return f"""请从下面的简历文本中抽取信息。

【今天日期】{today}（用于换算"至今"这类开放区间的工作年限）

【输出 JSON Schema】
{json.dumps(schema_json, ensure_ascii=False, indent=2)}

【简历文本开始】
{text}
【简历文本结束】

请输出 JSON："""


def extract_profile(raw_text: str) -> tuple[ResumeProfile, dict[str, int], int]:
    """返回 (profile, tokens, cost_ms)。"""
    started = time.time()
    profile, tokens = structured_call(
        _SYSTEM,
        _user_prompt(raw_text, ResumeProfile.model_json_schema()),
        ResumeProfile,
    )
    profile.skills = normalize_skills(profile.skills)
    cost_ms = int((time.time() - started) * 1000)
    return profile, tokens, cost_ms
