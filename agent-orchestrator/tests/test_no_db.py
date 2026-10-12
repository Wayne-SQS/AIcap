"""结构性保证：编排层不得有任何数据库连接（契约 §5.3）。

契约原话：「这条测试的价值不在'通过'，而在**它会失败**」。

所以本文件除了扫描真实代码，还额外用**合成源码**做一次元测试 —— 把
「它会失败」变成机器每次都会验的事实，而不是靠人手改一行再改回来。
检查逻辑本身抽成了接受字符串的纯函数，元测试直接喂违规源码给它。

契约 §5.3 给了 `FORBIDDEN_MODULES` / `FORBIDDEN_ENV` / `FORBIDDEN_FILES`
的初值，命令式代码里用 `set`/`tuple` 落地。
"""

from __future__ import annotations

import ast
import re
import tomllib
from pathlib import Path

import pytest

ORCHESTRATOR_ROOT = Path(__file__).resolve().parent.parent
APP_DIR = ORCHESTRATOR_ROOT / "app"
PYPROJECT = ORCHESTRATOR_ROOT / "pyproject.toml"

FORBIDDEN_MODULES = frozenset(
    {"sqlalchemy", "pymysql", "mysql", "psycopg2", "sqlite3", "asyncpg", "pymongo"}
)
FORBIDDEN_ENV = ("DATABASE_URL", "DB_HOST", "MYSQL_URL")
FORBIDDEN_FILES = frozenset({"database.py", "models.py", "db.py"})

# 扫描时跳过的目录（虚拟环境自带一堆 DB 驱动，不是我们的代码）
_SKIP_DIRS = frozenset({".venv", "__pycache__", ".pytest_cache", "node_modules"})


# --------------------------------------------------------------------------
# 纯函数检查器（可被元测试直接喂合成源码）
# --------------------------------------------------------------------------


def imported_top_level_modules(source: str) -> set[str]:
    """源码里所有 import 的顶层模块名。

    用 AST 而不是正则：正则会把注释和字符串里的 `import sqlalchemy` 也算进去，
    那样检查器会因为误报而被人为放宽。
    """
    modules: set[str] = set()
    for node in ast.walk(ast.parse(source)):
        if isinstance(node, ast.Import):
            for alias in node.names:
                modules.add(alias.name.split(".")[0])
        elif isinstance(node, ast.ImportFrom):
            # level > 0 是相对导入，不可能是第三方 DB 驱动
            if node.module and node.level == 0:
                modules.add(node.module.split(".")[0])
    return modules


def db_imports_in(source: str) -> set[str]:
    """源码里命中的禁用模块。"""
    return imported_top_level_modules(source) & FORBIDDEN_MODULES


def db_env_names_in(source: str) -> list[str]:
    """源码里出现的禁用环境变量名。"""
    return [name for name in FORBIDDEN_ENV if name in source]


def db_drivers_in_dependencies(pyproject_text: str) -> set[str]:
    """依赖清单里命中的 DB 驱动。

    runtime 与 dev 两组都查 —— dev 依赖也一样会进 CI 环境。
    """
    data = tomllib.loads(pyproject_text)
    project = data.get("project", {})
    deps: list[str] = list(project.get("dependencies", []))
    for extra in project.get("optional-dependencies", {}).values():
        deps.extend(extra)

    found: set[str] = set()
    for dep in deps:
        # "uvicorn[standard]>=0.29" → "uvicorn"；"sqlalchemy>=2.0" → "sqlalchemy"
        name = re.split(r"[<>=!\[; ]", dep.strip(), maxsplit=1)[0].strip().lower()
        if name in FORBIDDEN_MODULES:
            found.add(name)
    return found


def _iter_app_sources():
    for path in APP_DIR.rglob("*.py"):
        if _SKIP_DIRS & set(path.parts):
            continue
        yield path


# --------------------------------------------------------------------------
# 真实代码的四项检查
# --------------------------------------------------------------------------


def test_pyproject_declares_no_db_driver():
    assert db_drivers_in_dependencies(PYPROJECT.read_text(encoding="utf-8")) == set()


def test_app_sources_import_no_db_module():
    offenders = {
        str(path.relative_to(ORCHESTRATOR_ROOT)): sorted(db_imports_in(path.read_text(encoding="utf-8")))
        for path in _iter_app_sources()
        if db_imports_in(path.read_text(encoding="utf-8"))
    }
    assert offenders == {}


def test_app_sources_reference_no_db_env():
    offenders = {
        str(path.relative_to(ORCHESTRATOR_ROOT)): db_env_names_in(path.read_text(encoding="utf-8"))
        for path in _iter_app_sources()
        if db_env_names_in(path.read_text(encoding="utf-8"))
    }
    assert offenders == {}


def test_no_db_module_files_exist():
    present = {
        str(path.relative_to(ORCHESTRATOR_ROOT))
        for path in ORCHESTRATOR_ROOT.rglob("*.py")
        if path.name in FORBIDDEN_FILES and not (_SKIP_DIRS & set(path.parts))
    }
    assert present == set()


# --------------------------------------------------------------------------
# 元测试：证明检查器真的会失败
# --------------------------------------------------------------------------


@pytest.mark.parametrize(
    ("snippet", "expected"),
    [
        ("import sqlalchemy", {"sqlalchemy"}),
        ("from sqlalchemy import create_engine", {"sqlalchemy"}),
        ("import pymysql\n", {"pymysql"}),
        ("from sqlite3 import connect", {"sqlite3"}),
        ("import os\nimport asyncpg\n", {"asyncpg"}),
    ],
)
def test_checker_detects_forbidden_import(snippet: str, expected: set[str]):
    """元测试：检查器对违规 import 必须报出来，否则它只是个恒绿的摆设。"""
    assert db_imports_in(snippet) == expected


def test_checker_ignores_forbidden_words_in_comments_and_strings():
    """反向元测试：注释/字符串里的词不该误报，否则检查器会被人为放宽。"""
    assert db_imports_in("# import sqlalchemy\n") == set()
    assert db_imports_in('NOTE = "sqlalchemy"\n') == set()


def test_checker_detects_forbidden_env_name():
    assert db_env_names_in('os.getenv("DATABASE_URL")') == ["DATABASE_URL"]


def test_checker_detects_forbidden_driver_in_dependencies():
    fake = '[project]\ndependencies = ["sqlalchemy>=2.0", "httpx>=0.27"]\n'
    assert db_drivers_in_dependencies(fake) == {"sqlalchemy"}


def test_checker_detects_forbidden_driver_in_dev_dependencies():
    fake = (
        "[project]\ndependencies = [\"httpx\"]\n"
        "[project.optional-dependencies]\ndev = [\"psycopg2>=2.9\"]\n"
    )
    assert db_drivers_in_dependencies(fake) == {"psycopg2"}
