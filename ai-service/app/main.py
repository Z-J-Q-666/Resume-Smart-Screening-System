"""简历智能筛选系统 —— AI 服务入口。

只做两件事：
    POST /extract   PDF 文件 -> 结构化 Profile
    POST /match     Profile + 岗位 -> 语义分 + 理由
不碰数据库、不碰业务规则、不碰状态流转。
"""

import logging
import time
from contextlib import asynccontextmanager

from fastapi import FastAPI, File, Request, UploadFile
from fastapi.responses import JSONResponse

from .config import settings
from .extract_chain import extract_profile
from .llm import LLMFailure, SchemaViolation
from .match_chain import match_profile
from .pdf_reader import NoTextLayerError, extract_text
from .schemas import MatchRequest

logging.basicConfig(
    level=logging.INFO,
    format="%(asctime)s %(levelname)-5s [%(name)s] %(message)s",
    datefmt="%H:%M:%S",
)
logger = logging.getLogger("ai.service")

# 错误码与需求 §6.4 对齐
E_NO_TEXT_LAYER = 2001
E_LLM_FAILURE = 2002
E_SCHEMA_VIOLATION = 2003
E_BAD_REQUEST = 1001

MAX_PDF_BYTES = 10 * 1024 * 1024


def ok(data: dict) -> JSONResponse:
    return JSONResponse({"code": 0, "message": "ok", "data": data})


def fail(code: int, message: str, http_status: int = 200) -> JSONResponse:
    logger.warning("业务失败 code=%s message=%s", code, message)
    return JSONResponse({"code": code, "message": message, "data": None}, status_code=http_status)


@asynccontextmanager
async def lifespan(app: FastAPI):
    logger.info(
        "AI 服务启动 | model=%s base_url=%s max_text=%s",
        settings.LLM_MODEL,
        settings.LLM_BASE_URL,
        settings.MAX_TEXT_CHARS,
    )
    yield
    logger.info("AI 服务关闭")


app = FastAPI(title="Resume Screening AI Service", version="1.0.0", lifespan=lifespan)


@app.get("/health")
def health():
    return {
        "code": 0,
        "message": "ok",
        "data": {
            "status": "UP",
            "model": settings.LLM_MODEL,
            "baseUrl": settings.LLM_BASE_URL,
        },
    }


@app.post("/extract")
async def extract(file: UploadFile = File(...)):
    t0 = time.time()
    filename = file.filename or "resume.pdf"

    content = await file.read()
    if not content:
        return fail(E_BAD_REQUEST, "上传文件为空")
    if len(content) > MAX_PDF_BYTES:
        return fail(E_BAD_REQUEST, "文件超过 10MB 限制")
    if not content.startswith(b"%PDF"):
        return fail(E_BAD_REQUEST, "文件不是有效的 PDF")

    try:
        raw_text, page_count = extract_text(content)
    except NoTextLayerError as e:
        logger.warning("无文本层 file=%s", filename)
        return fail(E_NO_TEXT_LAYER, str(e))

    # 送模型的文本做截断，落库的 raw_text 保持全文
    model_text = raw_text[: settings.MAX_TEXT_CHARS]

    try:
        profile, tokens, llm_ms = extract_profile(model_text)
    except LLMFailure as e:
        return fail(E_LLM_FAILURE, str(e))
    except SchemaViolation as e:
        return fail(E_SCHEMA_VIOLATION, str(e))

    cost_ms = int((time.time() - t0) * 1000)
    logger.info(
        "抽取完成 file=%s pages=%s chars=%s cost_ms=%s llm_ms=%s tokens=%s skills=%s",
        filename,
        page_count,
        len(raw_text),
        cost_ms,
        llm_ms,
        tokens.get("total"),
        len(profile.skills),
    )

    return ok(
        {
            "rawText": raw_text,
            "pageCount": page_count,
            "profile": profile.model_dump(),
            "costMs": cost_ms,
            "tokens": tokens,
        }
    )


@app.post("/match")
async def match(req: MatchRequest, request: Request):
    t0 = time.time()
    if not (req.jd_text or "").strip() and not req.must_skills:
        return fail(E_BAD_REQUEST, "岗位缺少 JD 文本与必备技能，无法评估")

    try:
        result, tokens, llm_ms = match_profile(req)
    except LLMFailure as e:
        return fail(E_LLM_FAILURE, str(e))
    except SchemaViolation as e:
        return fail(E_SCHEMA_VIOLATION, str(e))

    cost_ms = int((time.time() - t0) * 1000)
    logger.info(
        "匹配完成 job=%s score=%s cost_ms=%s llm_ms=%s tokens=%s",
        req.job_title,
        result.semantic_score,
        cost_ms,
        llm_ms,
        tokens.get("total"),
    )

    return ok(
        {
            "semanticScore": result.semantic_score,
            "reason": result.reason,
            "costMs": cost_ms,
            "tokens": tokens,
        }
    )
