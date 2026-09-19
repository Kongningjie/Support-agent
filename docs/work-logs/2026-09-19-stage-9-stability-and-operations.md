# 阶段工作记录：稳定性与运行准备

## 基本信息

| 字段 | 内容 | 字段含义 |
|---|---|---|
| 执行日期 | 2026-09-19 | 本阶段实际实施日期 |
| 阶段 | stage-9-stability-and-operations | 二期优化计划第 9 阶段 |
| 关联计划 | [阶段 9](../implementation-plan/08-phase-2-optimization-plan.md#8-阶段-9稳定性与运行准备) | 本阶段唯一实施依据 |
| 状态 | 已完成 | 代码、恢复演练、容量门禁、在线测试和完整 Review 均已完成 |

## 本阶段目标

- 配置化并校验外部依赖的连接、读取、调用超时及既有运行参数。
- 增加 Outbox 积压、等待、重试、死亡任务和处理吞吐指标。
- 补充依赖异常、超时、降级和资源释放回归测试。
- 清理或明确处理 Mockito、Redis 测试关闭和 SpringDoc/Jackson 已知告警。
- 不改变一期公共接口、业务状态、技术栈和安全边界。

## 开始前检查

- 当前分支为 `main`。
- 开始时工作区已有用户未提交改动：`.gitignore`、三个模型适配器、`ChatUseCase`、`RedisConversationStoreAdapter` 和四张架构图。本阶段必须保留且不得将其误记为本阶段成果。
- 当前实现已经具备模型生成参数、Embedding 固定超时与有限重试、基础 Micrometer 耗时指标；本阶段在这些基础上补齐，不重复建立平行机制。

## 实施内容

### 外部依赖超时与有限重试

- 为 MySQL JDBC、Hikari、Redis/Lettuce 和 Elasticsearch REST 客户端补充显式连接、连接池等待、读取或命令超时；所有字段均在 `application.yml` 与 `.env.example` 说明含义和单位。
- 为 Embedding 与 Rerank 幂等只读调用增加最多三次、指数退避并带随机抖动的有限重试；确定性的鉴权、客户端状态码和响应结构错误不重试。
- Embedding 超时会取消等待任务，中断信号不会被误重试；Elasticsearch 开启硬取消。Chat 继续沿用阶段 8 已冻结的完整生成超时和安全输出机制。
- 会话 TTL、建议 TTL、聊天/建议租约、Outbox 退避序列与续租时长改为配置项，并在构造或启动阶段拒绝非正值、空序列及“租约不短于数据 TTL”等危险配置。

### Outbox 可观测性

- 增加单次聚合 SQL，统计非终态积压量、最老任务创建时间、累计额外尝试次数、`DEAD` 数量和最近一分钟成功吞吐。
- 定时采样到五个低基数 Micrometer Gauge；指标抓取只读取内存原子值，不会让每次 Prometheus 抓取访问 MySQL。
- 空任务表使用 `COALESCE` 返回零；采样失败保留上次成功快照，不影响业务请求且不记录底层供应商响应。

### 恢复、兼容性与资源释放

- 新增 [本地恢复手册](../../deploy/RECOVERY.md)，明确 MySQL 逻辑备份/恢复、Elasticsearch 索引重建、Redis 丢失降级及恢复后检查步骤。
- 自动验证 MySQL 8.4.11 “备份—删除—恢复”、Elasticsearch “删索引—重建映射—重写代表性来源—校验”和 Redis `FLUSHALL` 后旧会话安全过期、新会话可创建。
- 测试上下文显式关闭 Spring 资源，避免容器停止后 Lettuce 后台重连；Mockito 通过显式 Java Agent 加载，消除动态自附加警告。
- 将本阶段触达的 Jackson 过时文本读取 API 改为 Jackson 3 接口，并为缺失 CJK 映射增加安全判断。SpringDoc 3.1.0 仍会输出内部 Schema 转换告警，但 `/v3/api-docs` 契约测试通过；按冻结要求不盲目升级 Spring Boot 或替换文档栈，作为上游兼容性观察项保留。

## 测试与演练结果

| 命令/验证 | 结果 | 说明 |
|---|---|---|
| `mvn test` | 通过 | 135 项单元及架构测试，0 失败、0 错误、0 跳过 |
| `mvn verify -Pintegration` | 通过 | 23 项 Docker/启动集成测试；其中基础设施 18 项、启动链路 5 项 |
| `mvn verify -Ponline-test` | 通过 | 6 项真实 DashScope 测试：Chat、20 并发、Embedding、Rerank 和固定中文检索集 |
| `mvn verify -Pstage-6-baseline` | 通过 | 质量基线与完整容量基线均通过，动态报告未纳入 Git |

容量报告 `capacity-02bcb187-8c40-408b-ab6f-e9fb80aecdae.json` 的关键结果：

- 共 831 个请求，全部成功且无错误分类。
- 20 并发三轮成功率均为 100%，P95 分别为 83、84、86 ms。
- 持续 2 RPS/300 秒共 600 次，成功率 100%，P95 40 ms。
- 峰值 5 RPS/30 秒共 150 次，成功率 100%，P95 34 ms。

现有 Worker 并发和连接池未出现积压或容量不足证据，因此本阶段不调整并发，不引入 RocketMQ。

## Review 记录

- Review 范围：工作区全部代码、配置、SQL、测试、部署说明和本阶段记录；用户原有 `.gitignore`、模型适配器格式调整、聊天/Redis 注释及四张架构图单独识别并保留。
- 第一轮发现并修复：空 Outbox 表聚合可能返回 `NULL`；Jackson 3 在缺失 CJK 字段调用 `stringValue()` 会抛异常；Hikari 超时错误使用 `5s/3s` 而其属性要求毫秒整数；若干注释和缩进与实际配置不一致。
- 第二轮检查：`git diff --check`、敏感信息扫描、模块边界与配置字段核对均通过；未发现密钥、`.env`、供应商正文、临时代码或越过阶段 9 的业务扩展。
- Review 结论：通过。当前改动可以进入人工提交环节，但本次按要求不提交、不推送。

## 已知边界

- Elasticsearch 已验证物理索引和单来源重建机制，但当前没有面向运维的一键全量重建入口；不得篡改历史 Outbox 记录模拟重放，生产上线前应单独补齐受控全量重建工具。
- SpringDoc/Jackson 内部告警不影响当前 OpenAPI 端点和契约，后续仅在兼容版本有明确证据时升级。
- 本阶段未增加认证授权、长期记忆、消息队列、模块或公共业务契约。
