# support-agent-infrastructure

MySQL、Redis、Elasticsearch、Flyway、异步任务及运行观测适配层。

当前实现 MyBatis Repository、V1～V8 数据库迁移、Redis 会话/摘要/Token/登录退避、Elasticsearch 知识索引与检索、持久化 Outbox Worker、Micrometer 指标以及固定检索和 LLM 安全评测数据加载。MySQL 是业务事实源，Elasticsearch 是可重建派生索引。
