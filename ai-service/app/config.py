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

    RESOURCES_DIR: Path = BASE_DIR / "resources"


settings = Settings()
