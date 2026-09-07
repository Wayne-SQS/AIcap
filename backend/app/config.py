import os
from dotenv import load_dotenv

# backend/.env 位于 app/ 的上一级
load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))

DATABASE_URL = os.getenv("DATABASE_URL", "mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap?charset=utf8mb4")
TEST_DATABASE_URL = os.getenv("TEST_DATABASE_URL", "mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_test?charset=utf8mb4")
JWT_SECRET = os.getenv("JWT_SECRET", "dev-secret-change-me")
JWT_ALGO = os.getenv("JWT_ALGO", "HS256")
JWT_EXPIRE_MINUTES = int(os.getenv("JWT_EXPIRE_MINUTES", "720"))

# Meeting Agent: no key means disabled (manual review remains usable).
AICAP_LLM_API_KEY = (os.getenv("AICAP_LLM_API_KEY") or os.getenv("DEEPSEEK_API_KEY", ""))
AICAP_LLM_BASE_URL = os.getenv("AICAP_LLM_BASE_URL", "https://api.deepseek.com")
AICAP_LLM_MODEL = os.getenv("AICAP_LLM_MODEL", "deepseek-v4-flash")
AICAP_LLM_TIMEOUT = 45
AICAP_AGENT_MAX_STEPS = 6
AICAP_AGENT_MAX_SECONDS = 180
AICAP_AGENT_LEASE_SECONDS = 300
AICAP_AGENT_WORKER_ENABLED = os.getenv("AICAP_AGENT_WORKER_ENABLED", "true").lower() == "true"
