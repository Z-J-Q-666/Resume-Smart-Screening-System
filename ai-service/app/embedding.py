"""文本向量化。

为什么不把它做成强依赖：DeepSeek 没有 embedding 接口，得单独接一家（百炼/智谱/本地 BGE）。
大部分开发环境不会配这个 key。所以这里设计成"能配就用，没配就退回本地相似度"——
功能永远可用，只是精度不同。这种降级比"没有向量化就不能打分"务实得多。
"""

import logging
from typing import Optional

from openai import OpenAI

from .config import settings

logger = logging.getLogger("ai.embed")

# embedding 接口普遍有 token 上限，超了会整条报错。简历画像用不了这么长
MAX_CHARS = 2000

_client: Optional[OpenAI] = None


class EmbeddingUnavailable(Exception):
    """未配置向量化服务。调用方应该降级，而不是把请求判失败。"""


def available() -> bool:
    return bool(settings.EMBED_BASE_URL and settings.EMBED_API_KEY and settings.EMBED_MODEL)


def _client_or_raise() -> OpenAI:
    global _client
    if not available():
        raise EmbeddingUnavailable("未配置 EMBED_BASE_URL / EMBED_API_KEY / EMBED_MODEL")
    if _client is None:
        _client = OpenAI(
            api_key=settings.EMBED_API_KEY,
            base_url=settings.EMBED_BASE_URL,
            timeout=30,
            max_retries=1,
        )
        logger.info("向量化客户端就绪 model=%s base=%s", settings.EMBED_MODEL, settings.EMBED_BASE_URL)
    return _client


def embed_texts(texts: list[str]) -> list[list[float]]:
    """批量向量化。返回顺序与入参一致。"""
    client = _client_or_raise()
    # 空串会被接口直接拒绝，给个占位符；超长截断
    cleaned = [(t or "").strip()[:MAX_CHARS] or "空" for t in texts]

    resp = client.embeddings.create(model=settings.EMBED_MODEL, input=cleaned)
    ordered = sorted(resp.data, key=lambda d: d.index)
    vectors = [list(d.embedding) for d in ordered]
    logger.info("向量化完成 n=%s dim=%s", len(vectors), len(vectors[0]) if vectors else 0)
    return vectors


def model_name() -> str:
    return settings.EMBED_MODEL or "none"
