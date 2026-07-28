# ADR-007: 单体阶段中间件范围（拒绝微服务期中间件）

- **状态**: 已采纳
- **上下文**: 本地 `docker-dev` 已装 Nacos/Seata/RocketMQ/MongoDB 等微服务生态组件，有「顺手就用」之诱。
- **决策**: 单体阶段仅引入必需中间件（PostgreSQL / Redis / MinIO / RabbitMQ）；Nacos 服务发现、Seata、MongoDB、双 MQ 暂不引入。
- **后果**: 易 — 运维面小、故障域小、与 docker-deploy 一致；难 — 未来若抽微服务需补 Seata/Nacos（但那时才需要，可逆）。
