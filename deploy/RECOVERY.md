# 本地恢复手册

本文仅适用于 `deploy/compose.yml` 提供的本地开发环境。执行恢复前应停止应用写入，
确认备份文件、目标容器和目标数据库均正确；生产环境必须另行制定备份、权限、加密和
恢复审批方案。

## MySQL 备份与恢复

以下命令从容器内导出逻辑备份，密码仅由容器环境变量读取，不写入命令历史：

```powershell
New-Item -ItemType Directory -Force .\backups | Out-Null
docker compose -f .\deploy\compose.yml exec -T mysql sh -c 'exec mysqldump --single-transaction --routines --triggers -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"' > .\backups\support-agent.sql
```

恢复前应先在隔离环境演练。目标库为空且 Flyway 版本与备份一致时执行：

```powershell
Get-Content -Raw .\backups\support-agent.sql | docker compose -f .\deploy\compose.yml exec -T mysql sh -c 'exec mysql -u"$MYSQL_USER" -p"$MYSQL_PASSWORD" "$MYSQL_DATABASE"'
```

恢复后运行 `mvn verify -Pintegration`，并抽查 `flyway_schema_history`、业务记录和
`async_task` 状态。阶段 9 的 `InitialSchemaIT` 已在临时 MySQL 8.4.11 容器中自动验证
“写入标记数据—逻辑备份—删除—恢复—查询”的完整链路。

## Elasticsearch 索引丢失

Elasticsearch 只保存可重建的检索副本，MySQL 仍是业务事实来源。索引缺失时，适配器的
`ensureReady()` 会按冻结映射重建物理索引；随后必须从 MySQL 中仍有效的已发布文档和案例
重新生成分块、Embedding 并写入索引，最后按来源版本、分块数量和内容哈希校验。

阶段 9 的集成测试已验证“删除物理索引—重建映射—重新写入代表性来源—检索命中”。当前
版本没有面向运维人员的一键全量重建入口，不得通过篡改历史 `async_task` 记录伪造重放。
如果发生全量索引丢失，应先停止写入并保留 MySQL，再建立受控重建任务；生产上线前必须
把全量重建工具作为独立运维能力补齐。

## Redis 数据丢失

Redis 保存 7 天会话、24 小时建议以及短期租约，不是业务事实来源。数据丢失后不从 MySQL
回填会话：旧 `conversationId` 按 `CHAT_CONVERSATION_EXPIRED` 安全失败，客户端创建新会话
即可继续。不得把 Redis 故障解释为“知识库无结果”，也不得返回模型半成品答案。

阶段 9 的 `RedisConversationStoreAdapterIT` 已验证清空 Redis 后旧会话安全失效、新会话可用。

## 恢复完成检查

- `/actuator/health/liveness` 为 `UP`，必要依赖的 readiness 状态符合预期。
- Outbox 的积压量、最老等待时间、额外重试数、`DEAD` 数和每分钟吞吐恢复正常。
- `mvn test`、`mvn verify -Pintegration` 通过；经授权后再运行 `mvn verify -Ponline-test`。
- 恢复过程、备份时间、验证结果和遗留风险写入对应阶段工作记录。
