"""AIcap 主管智能体编排层。

依赖方向（契约 §5.2，单向不得回指）：

    main → orchestrator → loop → java_client → java-backend
                              ↓
                           tools / events

`java_client` 是**唯一**发起 HTTP 的模块。
"""
