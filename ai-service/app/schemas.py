"""跨语言数据契约（与 docs/message-contract.md 保持一致）。

约定：
- 抽取出的 Profile 内部字段用 snake_case，与需求 §9.2 的 Pydantic 模型逐字对齐，
  Java 侧以 JSON 原样落库 parsed_json，不做字段改名。
- 接口外层元信息（rawText / costMs / semanticScore ...）用 camelCase，与 Java DTO 对齐。
"""

from typing import Literal, Optional

from pydantic import BaseModel, Field, field_validator


def _truncate(v: Optional[str], limit: int) -> Optional[str]:
    """长度超限时截断而非抛错。

    真正的 Schema 校验（枚举取值、类型）失败仍然抛错并由调用方转成 2003；
    文本长度属于"模型话太多"，截断即可，不该让整份简历解析失败。
    """
    if v is None:
        return None
    v = v.strip()
    return v if len(v) <= limit else v[: limit - 1] + "…"


class Education(BaseModel):
    degree: Literal["大专", "本科", "硕士", "博士", "其他"]
    school: Optional[str] = None
    major: Optional[str] = None
    grad_year: Optional[int] = None

    @field_validator("school", "major")
    @classmethod
    def _short(cls, v: Optional[str]) -> Optional[str]:
        return _truncate(v, 100)


class Experience(BaseModel):
    company: str
    title: str
    period: Optional[str] = None
    # 注意：不要写成 Field(max_length=120)。Pydantic 的长度约束在 after 校验器之前执行，
    # 模型稍微啰嗦一点就会整份简历判 2003。长度统一由下面的校验器截断。
    summary: str = ""

    @field_validator("company", "title")
    @classmethod
    def _short(cls, v: str) -> str:
        return (_truncate(v, 100) or "") if v is not None else ""

    @field_validator("summary")
    @classmethod
    def _summary(cls, v: str) -> str:
        return _truncate(v, 120) or ""


class ResumeProfile(BaseModel):
    name: Optional[str] = None
    phone: Optional[str] = None
    email: Optional[str] = None
    education: Optional[Education] = None
    total_years: Optional[float] = None
    skills: list[str] = Field(default_factory=list)
    experiences: list[Experience] = Field(default_factory=list)
    summary: str = ""

    @field_validator("name")
    @classmethod
    def _name(cls, v: Optional[str]) -> Optional[str]:
        return _truncate(v, 50)

    @field_validator("phone")
    @classmethod
    def _phone(cls, v: Optional[str]) -> Optional[str]:
        return _truncate(v, 20)

    @field_validator("email")
    @classmethod
    def _email(cls, v: Optional[str]) -> Optional[str]:
        return _truncate(v, 100)

    @field_validator("summary")
    @classmethod
    def _summary(cls, v: str) -> str:
        return _truncate(v, 200) or ""


class MatchRequest(BaseModel):
    job_title: str = ""
    jd_text: str = ""
    must_skills: list[str] = Field(default_factory=list)
    plus_skills: list[str] = Field(default_factory=list)
    profile: ResumeProfile


class MatchResult(BaseModel):
    semantic_score: int = Field(ge=0, le=100)
    reason: str = ""

    @field_validator("reason")
    @classmethod
    def _reason(cls, v: str) -> str:
        return _truncate(v, 200) or ""
