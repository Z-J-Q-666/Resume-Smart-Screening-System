"""PDF 文本层提取（pdfplumber，对中文文本层支持好）。"""

import io
import re
from typing import BinaryIO

import pdfplumber

from .config import settings


class NoTextLayerError(Exception):
    """扫描件 / 图片型 PDF，没有可用文本层。"""


def _clean(text: str) -> str:
    # 压缩过多空行，保留段落结构
    text = text.replace("\u00a0", " ").replace("\u3000", " ")
    text = re.sub(r"[ \t]+", " ", text)
    text = re.sub(r"\n{3,}", "\n\n", text)
    return text.strip()


def extract_text(stream: BinaryIO | bytes) -> tuple[str, int]:
    """返回 (全文, 页数)；无文本层抛 NoTextLayerError。"""
    if isinstance(stream, (bytes, bytearray)):
        stream = io.BytesIO(stream)

    chunks: list[str] = []
    page_count = 0
    with pdfplumber.open(stream) as pdf:
        page_count = len(pdf.pages)
        for page in pdf.pages:
            try:
                chunks.append(page.extract_text() or "")
            except Exception:
                # 单页失败不影响其它页
                continue

    text = _clean("\n".join(chunks))

    # 乱码判定：可读字符占比过低，不硬喂给模型（需求 §13 风险对策）
    if text:
        readable = sum(1 for ch in text if ch.isalnum() or "\u4e00" <= ch <= "\u9fff")
        if readable / max(len(text), 1) < 0.25:
            raise NoTextLayerError("PDF 文本层无法识别（疑似乱码或扫描件），请上传文本型 PDF")

    if len(text) < settings.MIN_TEXT_CHARS:
        raise NoTextLayerError("未检测到文本层，请上传文本型 PDF")

    return text, page_count
