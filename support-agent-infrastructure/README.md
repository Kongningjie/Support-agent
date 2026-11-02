# support-agent-infrastructure

聊天治理步骤五已补齐：Redis begin 在所有者、空间和消息内容校验后优先重放当前代际已完成结果；新执行仍严格校验版本，不放宽代际隔离或运行围栏。无新增数据库迁移。

MySQL、Redis、Elasticsearch、Flyway、异步任务及运行观测适配层。

当前实现 MyBatis Repository、V1～V12 数据库迁移、Redis 会话/摘要/Token/登录退避、Elasticsearch v2 空间化知识索引与范围检索、持久化 Outbox Worker、Micrometer 指标，以及固定检索、LLM 安全和 60 条知识空间隔离评测数据加载与报告。MySQL 是业务事实源，Elasticsearch 是通过稳定别名切换的可重建派生索引。

聊天断连治理步骤四提供按所有者、代际、消息和 runId 的 Redis 原子提交核实/失败终结；核实不调用 begin、不更改后续运行。complete 同时检查活动消息和运行状态，原子失败后的迟到提交被拒绝。MySQL 运行审计仅允许 RUNNING 转终态，避免迟到成功/失败互相覆盖；本步骤没有新增数据库迁移。
