"""技能名称归一化（需求 FR-AI-03 / 附录 A）。"""

import json
import re
from typing import Iterable, Optional

from .config import settings

_ALIAS: dict[str, str] = {}
# 反向索引：标准写法 -> 标准写法，保证已经规范的词不会再被拆坏
_CANONICAL: set[str] = set()


def _load() -> None:
    global _ALIAS, _CANONICAL
    path = settings.RESOURCES_DIR / "skill_alias.json"
    with open(path, "r", encoding="utf-8") as f:
        _ALIAS = {k.strip().lower(): v for k, v in json.load(f).items()}
    _CANONICAL = set(_ALIAS.values())


_load()


def _key(s: str) -> str:
    """比较用的键：小写、去空白、去末尾版本号干扰符。"""
    return re.sub(r"\s+", " ", s.strip().lower())


def normalize_one(raw: Optional[str]) -> Optional[str]:
    if not raw:
        return None
    s = _key(str(raw))
    if not s:
        return None
    if s in _ALIAS:
        return _ALIAS[s]
    # 已是标准写法（大小写不敏感）
    for c in _CANONICAL:
        if c.lower() == s:
            return c
    # 去掉括号补充说明后重试，如 "Spring Boot（熟练）"
    stripped = re.sub(r"[（(].*?[)）]", "", s).strip()
    if stripped and stripped != s and stripped in _ALIAS:
        return _ALIAS[stripped]
    # 表里没有的词保留原文大小写，不乱猜
    return str(raw).strip()


def normalize_skills(skills: Iterable[str]) -> list[str]:
    """归一化 + 去重，保持首次出现顺序。"""
    seen: set[str] = set()
    out: list[str] = []
    for s in skills or []:
        n = normalize_one(s)
        if not n:
            continue
        k = _key(n)
        if k in seen:
            continue
        seen.add(k)
        out.append(n)
    return out


def canonical_key(s: str) -> str:
    """给 Java 侧做集合运算时用的对齐口径（同 _key）。"""
    n = normalize_one(s) or s
    return _key(n)
