"""LLM 客户端 + 两条 LangChain 链（抽取 / 匹配）。

刻意只用一个 ChatOpenAI 实例 + with_structured_output：
- 不用 Agent，不用 tool 循环，不需要记忆
- json_mode 而不是 json_schema：DeepSeek 只支持 response_format=json_object
"""

import logging
import time
from typing import Any, Optional, Type

from langchain_core.language_models import BaseChatModel
from langchain_core.messages import HumanMessage, SystemMessage
from langchain_openai import ChatOpenAI
from pydantic import BaseModel

from .config import settings

logger = logging.getLogger("ai.llm")

_llm: Optional[BaseChatModel] = None


def get_llm() -> BaseChatModel:
    global _llm
    if _llm is None:
        _llm = ChatOpenAI(
            model=settings.LLM_MODEL,
            api_key=settings.LLM_API_KEY,
            base_url=settings.LLM_BASE_URL,
            temperature=settings.LLM_TEMPERATURE,
            timeout=settings.LLM_TIMEOUT,
            max_retries=0,  # 重试统一由 Java 消费端控制，避免双层重试放大延迟
        )
    return _llm


class SchemaViolation(Exception):
    """模型输出不符合 Schema（对应错误码 2003）。"""


class LLMFailure(Exception):
    """LLM 调用失败 / 超时（对应错误码 2002）。"""


def _token_usage(raw: Any) -> dict[str, int]:
    usage = getattr(raw, "usage_metadata", None) or {}
    if usage:
        return {
            "prompt": int(usage.get("input_tokens", 0) or 0),
            "completion": int(usage.get("output_tokens", 0) or 0),
            "total": int(usage.get("total_tokens", 0) or 0),
        }
    meta = (getattr(raw, "response_metadata", None) or {}).get("token_usage", {}) or {}
    return {
        "prompt": int(meta.get("prompt_tokens", 0) or 0),
        "completion": int(meta.get("completion_tokens", 0) or 0),
        "total": int(meta.get("total_tokens", 0) or 0),
    }


def structured_call(
    system_prompt: str,
    user_prompt: str,
    schema: Type[BaseModel],
    schema_attempts: int = 2,
) -> tuple[BaseModel, dict[str, int]]:
    """调用模型并拿到 Pydantic 对象。Schema 不合规重试，网络/超时直接抛。"""
    runnable = get_llm().with_structured_output(schema, method="json_mode", include_raw=True)
    messages = [SystemMessage(content=system_prompt), HumanMessage(content=user_prompt)]

    last_error: Optional[Exception] = None
    for attempt in range(1, schema_attempts + 1):
        started = time.time()
        try:
            out = runnable.invoke(messages)
        except Exception as e:  # 网络、超时、鉴权、额度
            logger.error("LLM 调用失败 attempt=%s err=%s", attempt, e)
            raise LLMFailure(f"LLM 调用失败：{type(e).__name__}: {e}") from e

        raw = out.get("raw") if isinstance(out, dict) else None
        parsing_error = out.get("parsing_error") if isinstance(out, dict) else None
        parsed = out.get("parsed") if isinstance(out, dict) else out

        tokens = _token_usage(raw)
        logger.info(
            "LLM 返回 attempt=%s cost_ms=%s prompt_tokens=%s completion_tokens=%s",
            attempt,
            int((time.time() - started) * 1000),
            tokens.get("prompt"),
            tokens.get("completion"),
        )

        if parsing_error is None and isinstance(parsed, schema):
            return parsed, tokens

        last_error = SchemaViolation(str(parsing_error or "模型未返回符合 Schema 的 JSON"))
        logger.warning("Schema 校验失败 attempt=%s err=%s", attempt, last_error)
        # 第二轮把错误回灌，让模型自己修正
        messages = messages + [
            HumanMessage(
                content=(
                    "上一次输出不符合要求，请严格按要求重做。错误信息："
                    f"{last_error}\n只输出 JSON，不要任何解释文字。"
                )
            )
        ]

    raise SchemaViolation(f"抽取结果不符合 Schema：{last_error}")
