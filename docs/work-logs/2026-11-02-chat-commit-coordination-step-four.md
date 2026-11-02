# 聊天断连取消步骤四：最终提交与响应不确定

日期：2026-11-02。状态：步骤四已完成，整体批次仍进行中。依据：[实施计划步骤四](../implementation-plan/18-chat-disconnect-cancellation-implementation-plan.md)。开始时 main 与 origin/main 一致，工作区干净，前一提交为 3b9dc2f。本次不执行步骤五，不提交或推送。

## 范围

已阅读总计划、当前基线、对应冻结方案、完整实施计划、开发规范和最新步骤三记录。沿用既有模块与依赖，不读取 .env，不调用真实模型，不新增对外字段、SSE 事件、数据库状态或迁移。

- 请求控制增加与 cancel 共用短锁的一次性提交权；取消仍关闭发送，提交获胜后不回滚。
- begin 原子返回内部 generation 快照，保留原版本/重放判定顺序，后续步骤才调整旧版本重放。
- Redis 原子 resolveCommitOrFail 检查所有者、代际、消息及 runId；返回已提交结果、原子失败终结或归属不符，不触碰新运行。无法核实时保持未知，不盲目 fail。
- 已提交结果与成功审计故障分开；审计终态使用 RUNNING 条件更新，禁止迟到失败覆盖成功及反向覆盖。

## 验证与 Review

新增 10 项单元测试、11 项集成测试。单元覆盖提交门闩两种先后及 100 次竞争、取消后不提交、提交获胜后取消、响应丢失核实成功、核实失败后错误发送再次断连不重复清理、核实未知/未拥有时不猜终态、不启动可选后处理、成功审计故障以及明确围栏拒绝保持原冲突语义。

真实 Redis 覆盖核实与 complete 的两种先后、20 次并发竞争、重复核实、后续新运行不受影响、错误所有者/消息、删除/重置后的代际隔离。真实 MySQL 验证 RUNNING 条件终结、成功/失败互不覆盖及首次终止原因不被重复写改动。完整应用真实 HTTP/SSE 故障注入覆盖：实际 Redis complete 执行后丢失响应、未执行即报错、核实也不可用、成功审计故障；原真实浏览器断连到上游 EOF/运行清理回归继续通过。故障注入使用测试源集 Spy 控制异常边界，保存、核实和状态检查仍使用真实容器；没有声称模拟异常等于真实网络分区实验。

| 命令/检查 | 结果 |
|---|---|
| `mvn -q -pl support-agent-infrastructure -am test` | 定向单元门禁通过 |
| `mvn -q -pl support-agent-bootstrap -am verify -Pintegration '-Dit.test=RedisConversationStoreAdapterIT,FoundationRepositoryIT,ChatDisconnectCleanupIT' '-Dfailsafe.failIfNoSpecifiedTests=false'` | 定向真实容器/应用故障注入通过；随后 Review 的明确拒绝分支纳入完整门禁 |
| `mvn -q test` | 全量通过；最终报告 369 项单元/接口测试，失败、错误、跳过均为 0 |
| `mvn -q verify -Pintegration` | 全量通过；最终报告 369 项单元/接口测试、86 项集成测试，失败、错误、跳过均为 0 |
| `git diff --check`、新增文件空白与 Markdown 链接核对 | 通过；与运行测试分别核对 |

完整 Review 范围：全部生产、测试、XML 和未跟踪记录差异；检查门闩共锁线性化且不在锁内 I/O、取消后提交权语义、内部代际快照、Lua 无查询后写入窗口、旧消息/新 runId 围栏、未知不写失败或成功、无模型重试及可选后处理、审计终态不覆盖、SSE 终结和错误发送、字段中文含义、Javadoc、模块方向与无密钥/正文日志。Review 修正了原子终结后错误发送失败可能再次进入普通 fail 的路径，并显式保留明确围栏拒绝的 CHAT_CONVERSATION_BUSY 语义；均增加回归后通过完整门禁。最终只调整注释和文档，无测试之后的行为变更；Review 无剩余阻断项。

## 内部字段与结果含义

| 内部项 | 含义 |
|---|---|
| `commitEntered` / `tryEnterCommit()` | 当前请求仅能取得一次提交权；与 cancel 使用同一短锁，取消先发生则拒绝提交；不阻止网络继续取消 |
| `BeginResult.generation` | begin 在同一次 Lua 中取得的会话代际快照，核实只能针对该代际；兼容构造器仅供原零代际隔离调用，不用于真实 Redis 猜测代际 |
| `CommitResolution.status` | COMMITTED：原轮次已保存；FAILED：同一运行已失败或被原子终结；REJECTED：complete 明确围栏拒绝；NOT_OWNED：归属/代际/消息/运行不符；UNKNOWN：存储不可达或结果缺失、不可解析 |
| `CommitResolution.turn` | 仅 COMMITTED 存在的原完整安全轮次；其 runId 和 clientMessageId 必须匹配核实目标 |

上述均为内部契约，不是 REST/SSE 新字段或数据库新状态。FAILED/REJECTED 只尝试一次失败审计，不再次普通 fail；REJECTED 沿用原忙/围栏错误。NOT_OWNED/UNKNOWN 不发送确定性成功/失败事件，不新增自动恢复请求。

## 边界与环境回收

- Redis 提交和核实沿用已有连接默认 2 秒、命令默认 3 秒的有界超时；锁只用于选择提交权，未在锁内等待存储。故障场景不承诺整个清理五秒内完成；既有五秒验收仅适用于本地健康环境检测取消后的直接模型链路。
- 核实不可用时记录未知，可能仍有活动租约，也可能实际已提交；不伪造终态，由现有幂等结果和租约接管恢复。旧版本已完成消息重放与前端结果未知提示仍待步骤五，不能把本步骤视为整个重放批次验收。
- MySQL 成功审计故障不会回滚 Redis；审计可能仍为 RUNNING，回答反馈暂不能使用该运行。仅安全告警，不新增审计补偿平台或自动重试。
- 未修改前端，不执行前端门禁；未读取 .env、调用真实 DashScope 或执行 online-test。测试容器已自动回收，未操作用户其他项目容器；本地 HTTP/socket 和执行器随测试释放。
- 总计划、当前基线、冻结方案增量、索引和相关 README 同步本步骤状态；旧日志保留当时结论。完整批次指标、重放与前端留待步骤五。未提交或推送。

用户随后授权推送。提交前复核本次全部文件与已完成 Review 的范围一致，未混入 .env、密钥、构建产物或无关变更，目标分支为 main。代码没有继续修改，沿用已通过的 369 项单元/接口测试和 86 项集成测试，不重复运行；此次只提交、推送步骤四，不执行步骤五。
