# 知识空间运行与恢复手册

## 1. 适用范围

本文用于四期知识空间的创建、成员治理、停用、全量索引重建、完整性核对和故障恢复。所有命令使用 Windows 11 PowerShell 7。不得通过本文绕过应用状态、权限、审计或乐观锁直接修改生产数据。

## 2. 创建与成员治理

1. 平台管理员通过 `POST /api/v1/admin/knowledge-spaces` 创建空间，默认 `RESTRICTED + ACTIVE`。
2. 使用响应中的 `spaceId` 和 `version`，通过成员接口添加至少一个 `MANAGER`；受限空间不得撤销或降级最后一个有效 `MANAGER`。
3. 再按需加入 `EDITOR`、`READER`。成员恢复、改角色和撤销必须提交当前 `expectedVersion`。
4. 每次操作后检查 `security_event` 中的空间或成员事件。禁止在日志或指标中查询空间名称、用户 ID 或正文标签。

请求示例见 `http/29-knowledge-spaces.http`。

## 3. 空间停用与恢复

- 停用前确认没有正在编辑的草稿或待发布任务，并记录业务原因。
- 调用正式停用 API；停用后新 Chat、检索、知识写入、工单创建、建议消费和案例发布必须失败。
- 已开始的 Chat 会在正文发送前重新鉴权；权限失效时只发送安全错误，不发送答案正文或引用。
- `KNOWLEDGE_INDEX` 在执行前发现停用空间会清理当前版本分块并以 `KNOWLEDGE_SPACE_DISABLED` 取消；删除任务仍可执行。
- 恢复必须调用正式启用 API 并记录审计；只有仍为当前 `PUBLISHED` 版本的来源可以恢复可见。

## 4. 全量重建与别名切换

1. 确认 MySQL、Elasticsearch 和 DashScope Embedding 可用，并记录当前别名指向的旧物理索引。
2. 调用 `POST /api/v1/admin/knowledge-index/rebuild`。应用会创建目标索引，逐来源执行生产分块、Embedding、Bulk 写入，并核对来源数量、版本、空间、内容哈希和总分块数。
3. 只有全部核对通过后才原子切换 `support_knowledge_current`；任何失败均不得手工强切别名。
4. 执行只读核对：

```powershell
$env:SUPPORT_AGENT_MYSQL_PASSWORD = "仅本机只读核对账号密码"
.\deploy\verify-knowledge-space-consistency.ps1 -MySqlPort 13307
```

脚本只执行 `SELECT` 和 Elasticsearch `_search`，比较每个已发布来源的版本、空间和分块数量，不写数据库或索引。

## 5. 失败恢复

- 重建失败：保留当前业务别名不变，修复来源、Embedding 或目标索引故障后重新执行完整重建；不得跳过完整性检查。
- 别名切换后异常：仅当旧索引仍保留、旧索引结构支持当前兼容应用且核对记录完整时，才可由管理员按已记录索引名原子回切。
- 数据库迁移：Flyway V9 只前进不回退，不执行 `undo`，不得删除 `space_id` 或 `GLOBAL` 数据。
- Redis 历史会话：仅保留阶段 19 前无 `spaceId` 会话的原 7 天 TTL 读取兼容；所有新写入必须携带空间，禁止延长无字段兼容数据寿命。

## 6. 回滚边界

- 应用兼容回滚窗口为阶段 20 发布后 7 天，与历史 Redis 会话最大 TTL 对齐。
- 旧 Elasticsearch 物理索引至少保留 7 天；完成两次完整性核对且确认无回切需求后，才能另行审批清理。
- 回滚应用必须能读取 V9 数据结构和 Elasticsearch v2 `spaceId`；不得回滚到会执行无空间检索或缺省 `GLOBAL` 新写入的版本。
- 数据库迁移、空间审计事件和资源空间归属不回退。

## 7. 验收与审计

- 执行 `mvn test`、`mvn verify -Pintegration`，并按 [前端冻结计划](../frontend/implementation-plan/00-frontend-phased-implementation-plan.md) 执行格式、Lint、类型、单元测试、构建及需要的真实后端 E2E 门禁。
- 固定 60 条数据位于 `support-agent-infrastructure/src/main/resources/evaluation/knowledge-space-cases.jsonl`；动态报告必须写入 `target/knowledge-space-evaluation/`。
- 核对跨空间 Rerank 候选、引用、回答事实和详情泄漏均为 0，撤权/禁用/停用拒绝率为 100%，且 `noHitAccuracy=1.00`、`exactTermRecall=1.00`。
- 未经单独授权不得执行或宣称通过真实 DashScope 在线测试。
