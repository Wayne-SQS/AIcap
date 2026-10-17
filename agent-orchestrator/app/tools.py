"""工具清单与调用转发。

清单**从 `/api/agents/catalog` 拉、不硬编码**（契约 §2.1 理由 2）：新增工具只改
Java 侧，本层零改动。进程内缓存一份。

本层不引任何第三方 SDK，且只经 `java_client` 发请求 —— 它不自己 `import httpx`。
"""

from __future__ import annotations

from typing import Any

from .java_client import JavaClient
from .schemas import Catalog, CatalogTool, ToolOutcome


class ToolRegistry:
    """平台工具清单的本地视图 + 调用转发。"""

    def __init__(self, client: JavaClient) -> None:
        self._client = client
        self._catalog: Catalog | None = None

    async def load(self, jwt: str, *, refresh: bool = False) -> Catalog:
        """拉取（或复用缓存的）能力清单。"""
        if self._catalog is None or refresh:
            self._catalog = await self._client.catalog(jwt)
        return self._catalog

    @property
    def loaded(self) -> bool:
        return self._catalog is not None

    def _require_loaded(self) -> Catalog:
        if self._catalog is None:
            raise RuntimeError("工具清单尚未加载，先 await load(jwt)")
        return self._catalog

    def names(self) -> list[str]:
        return [tool.name for tool in self._require_loaded().tools]

    def spec(self, name: str) -> CatalogTool | None:
        for tool in self._require_loaded().tools:
            if tool.name == name:
                return tool
        return None

    def limitations(self) -> list[str]:
        """平台能力声明 —— 主管规划时必须带进自己的 prompt（契约 §2.2）。"""
        return list(self._require_loaded().limitations)

    async def invoke(
        self,
        name: str,
        args: dict[str, Any] | None,
        jwt: str,
    ) -> ToolOutcome:
        return await self._client.call_tool(name, args, jwt)
