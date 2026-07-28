# ADR-018: AMQP 事件桥接改为默认启用（profile 移除）

- **状态**: 已采纳
- **上下文**: 原 ADR-018 将 AMQP 桥接收进 `amqp profile`，但实际增加了理解成本（2 个 profile 组合出 4 种理论模式，实际只关心开/关）。RabbitMQ 已在 docker-compose 默认启动，作为默认基础设施无需 profile 隔离。
- **决策**: AMQP 桥接（`VisitEventAmqpBridge` / `OrderCreatedEventAmqpBridge` / `VisitStatusEventAmqpBridge`）始终生效。RabbitMQ 连接已配置在主 `application.yml`，broker 不可用时 Spring AMQP 自动重试，不阻塞启动。启动命令简化为默认启动 + 可选 `--spring.profiles.active=iam`（仅认证）。
- **后果**: 易 -- 减少一个 profile 维度，默认即全功能（RabbitMQ 就绪后自动连上）；难 -- 本地开发需 docker-compose 启动 RabbitMQ（原本就是默认基础设施）。
