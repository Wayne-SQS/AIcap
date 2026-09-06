import os
from dotenv import load_dotenv

# backend/.env 位于 app/ 的上一级
load_dotenv(os.path.join(os.path.dirname(__file__), "..", ".env"))

DATABASE_URL = os.getenv("DATABASE_URL", "mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap?charset=utf8mb4")
TEST_DATABASE_URL = os.getenv("TEST_DATABASE_URL", "mysql+pymysql://aiguanli:aiguanli-2026@localhost:3307/AIcap_test?charset=utf8mb4")
JWT_SECRET = os.getenv("JWT_SECRET", "dev-secret-change-me")
JWT_ALGO = os.getenv("JWT_ALGO", "HS256")
JWT_EXPIRE_MINUTES = int(os.getenv("JWT_EXPIRE_MINUTES", "720"))
