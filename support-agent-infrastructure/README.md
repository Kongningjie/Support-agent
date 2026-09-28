# support-agent-infrastructure

MySQL、Redis、Elasticsearch、Flyway、异步任务及运行观测适配层。

当前实现 MyBatis Repository、V1～V9 数据库迁移、Redis 会话/摘要/Token/登录退避、Elasticsearch v2 空间化知识索引与范围检索、持久化 Outbox Worker、Micrometer 指标，以及固定检索、LLM 安全和 60 条知识空间隔离评测数据加载与报告。MySQL 是业务事实源，Elasticsearch 是通过稳定别名切换的可重建派生索引。
