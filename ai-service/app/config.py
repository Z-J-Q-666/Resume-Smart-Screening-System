from dotenv import load_dotenv
import os
from pathlib import Path

BASE_DIR = Path(__file__).resolve().parent.parent

load_dotenv(BASE_DIR / ".env")


class Settings:
    LLM_BASE_URL: str = os.getenv("LLM_BASE_URL", "https://api.deepseek.com/v1")
    LLM_API_KEY: str = os.getenv("LLM_API_KEY", "")
    LLM_MODEL: str = os.getenv("LLM_MODEL", "deepseek-chat")
    LLM_TIMEOUT: int = int(os.getenv("LLM_TIMEOUT", "60"))
    LLM_TEMPERATURE: float = float(os.getenv("LLM_TEMPERATURE", "0"))

    MAX_TEXT_CHARS: int = int(os.getenv("MAX_TEXT_CHARS", "8000"))
    MIN_TEXT_CHARS: int = int(os.getenv("MIN_TEXT_CHARS", "60"))

    # 向量化服务。DeepSeek 不提供 embedding 接口，需要单独配一个（百炼/智谱/本地都行，
    # 只要兼容 OpenAI 的 /v1/embeddings）。三项任一为空则视为不可用，调用方降级到本地相似度。
    EMBED_BASE_URL: str = os.getenv("EMBED_BASE_URL", "")
    EMBED_API_KEY: str = os.getenv("EMBED_API_KEY", "")
    EMBED_MODEL: str = os.getenv("EMBED_MODEL", "")

    RESOURCES_DIR: Path = BASE_DIR / "resources"


settings = Settings()
