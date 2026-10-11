"""依赖上界（任务 B）：直接依赖必须**上下界都有**，且区间必须放行本机实测的版本。

为什么值得一条专门的测试：`pyproject.toml` 里的区间是**声明**，没有任何东西会因为
它写错而立刻报警 —— 直到某天 `pip install -e .` 装进来一个不相容的大版本，或者
CI 在一个和本轮改动无关的地方炸掉。两种失败都离"是谁改了依赖"很远。

不联网、不装包：用 `importlib.metadata` 读**已安装**的版本，用 `packaging` 现算
它落不落在声明的区间里。这条正是"改了依赖但环境没变"的静默失效的解药。
"""

from __future__ import annotations

import tomllib
from importlib.metadata import PackageNotFoundError, version
from pathlib import Path

import pytest
from packaging.requirements import Requirement

PYPROJECT = Path(__file__).resolve().parent.parent / "pyproject.toml"


def direct_requirements() -> list[tuple[str, str]]:
    """`(依赖声明字符串, 分组)` —— runtime 与 dev 两组都算"直接依赖"。"""
    data = tomllib.loads(PYPROJECT.read_text(encoding="utf-8"))
    project = data["project"]
    declared: list[tuple[str, str]] = [(dep, "runtime") for dep in project.get("dependencies", [])]
    for extra, deps in project.get("optional-dependencies", {}).items():
        declared.extend((dep, f"extra:{extra}") for dep in deps)
    return declared


def test_there_are_direct_dependencies_to_check():
    """反向元测试：解析器坏了的话下面几条会**恒绿**，所以先钉住它真的读到了东西。"""
    declared = direct_requirements()

    assert len(declared) >= 5
    names = {Requirement(dep).name for dep, _ in declared}
    assert {"fastapi", "httpx", "pydantic", "uvicorn"} <= names


def test_every_direct_dependency_has_an_upper_bound():
    """只有下界 = 允许任意大版本进来，正是要修的那个洞。"""
    unbounded = [f"{group}: {dep}" for dep, group in direct_requirements() if "<" not in dep]

    assert unbounded == [], "这些依赖只写了下界，大版本漂移会直接炸：\n" + "\n".join(unbounded)


def test_no_direct_dependency_is_pinned_to_an_exact_version():
    """只加**上界**，不写 `==`：本层是库不是应用。

    锁死具体版本等于把兼容负担推给使用者（和 `ai-service/requirements.txt` 那种
    "应用部署物"是不同的东西），而且会逼着每次安全更新都改 pyproject。
    """
    pinned = [f"{group}: {dep}" for dep, group in direct_requirements() if "==" in dep]

    assert pinned == []


def test_declared_bounds_admit_the_installed_versions():
    """声明的区间必须放行**本机已装**的版本。

    上界写小一点（比如照抄成 `<0.144`）不会让任何测试变红，却会让
    `pip install -e .` 在别的机器上解析出一个和这里测过的完全不同的组合。
    """
    offenders: list[str] = []
    for dep, group in direct_requirements():
        requirement = Requirement(dep)  # "uvicorn[standard]>=0.29,<1.0"
        try:
            installed = version(requirement.name)
        except PackageNotFoundError:  # pragma: no cover - 环境缺件本身该报出来
            offenders.append(f"{group}: {dep} —— 本环境根本没有 {requirement.name}")
            continue
        if installed not in requirement.specifier:
            offenders.append(f"{group}: {dep} 不含已安装的 {installed}")

    assert offenders == [], "\n".join(offenders)


@pytest.mark.parametrize("package", ["fastapi", "httpx", "pydantic", "uvicorn", "python-dotenv"])
def test_upper_bounds_are_the_next_major_version(package: str):
    """上界取**下一个大版本**（`>=x,<y`，y = 下一个大版本号）。

    这是刻意选的粒度：比"锁死已装版本"宽松（不逼着每次 patch 都改文件），
    又挡住了会让响应模型/中间件契约失效的大版本跳变。
    """
    declared = {Requirement(dep).name: Requirement(dep) for dep, _ in direct_requirements()}
    requirement = declared[package]

    uppers = [spec.version for spec in requirement.specifier if spec.operator == "<"]
    assert uppers, f"{package} 没写上界"
    assert all(part == "0" for part in uppers[0].split(".")[1:]), (
        f"{package} 的上界 {uppers[0]} 不是大版本边界"
    )
