# 聊天断连取消步骤二：模型取消传播

日期：2026-11-02。状态：步骤二已完成；整体批次仍进行中。前置验证发现 SDK 取消阻塞后曾暂停，用户明确同意传输适配补齐后继续并完成门禁。依据：[实施计划步骤二](../implementation-plan/18-chat-disconnect-cancellation-implementation-plan.md)。本次仅授权模型取消传播及必要参数接线，不实施步骤三的全面业务治理、步骤四提交核实或步骤五重放与前端。

## 范围与验证

开始时 main 工作区干净，步骤一已提交。先检查当前 AgentScope Java 2.0.1 的实际 HTTP 传输取消能力，再实现请求级取消动作注册、显式端口控制参数和适配层可取消等待。不读取 .env，不调用真实模型，不增加依赖或中间件。

SDK 源码检查：默认 HttpTransportFactory 使用 JdkHttpTransport；其流式实现注册 future.cancel(true) 并释放响应 InputStream。OkHttp 可选实现语义不同，不能混用结论。必须用本地 HTTP 服务补足实验证据。

## 已执行验证与结果

新增 AgentScopeCancellationTransportIT：直接调用 SDK 默认 OpenAIChatModel，以本地原始 HTTP/1.1 socket 构造可控上游。测试消费请求后，一种场景暂不返回 HTTP 响应，另一种发送合法首 Token 后不再返回字节；取消操作在独立虚拟线程执行，检查五秒内返回，并通过服务端输入 EOF 验证连接关闭。无真实密钥或外网调用。

| 命令 | 结果 |
|---|---|
| mvn -q -pl support-agent-agent -am verify -Pintegration '-Dit.test=AgentScopeCancellationTransportIT' '-Dfailsafe.failIfNoSpecifiedTests=false' | 2 项 IT：首 Token 前取消通过；收到首 Token 后闲置流取消出现 TimeoutException，失败于 cancelled.get(5, TimeUnit.SECONDS)，未到 EOF 成功断言 |
| mvn -q -pl support-agent-agent -am verify -Pintegration '-Dit.test=AgentScopeCancellationTransportIT#shouldCloseDefaultTransportDuringIdleStream' '-Dfailsafe.failIfNoSpecifiedTests=false' -DskipTests=false | 单独复测同一场景，仍在取消操作等待五秒处失败；保留失败结果，不扩大超时或弱化断言 |

首次尝试未给含点号的 Maven 属性参数加引号，被 PowerShell 拆分导致 Maven 生命周期参数错误，未执行测试；修正引用后才运行上述验证。两次实际验证的前置模块单元测试均未报告失败。没有执行全量基础设施测试或在线测试，不沿用步骤一的 328/62 数量充当本步骤结果。

上游测试设置十秒 socket 超时作为失败场景的回收兜底；约十秒后由测试服务关闭连接，不是 SDK 五秒内取消成功。取消后 SDK 阻塞读取还记录了 closed/onErrorDropped 日志；未全局关闭日志或吞掉 Reactor 错误。临时连接、服务器和虚拟线程均已回收。

## 前置源码检查、Review 与暂停原因（当时记录）

SDK JdkHttpTransport 流式读取使用 BufferedReader 和 Flux.using，资源清理包含关闭 reader 和响应 InputStream。源码与阻塞现象提示读取/关闭顺序可能造成取消等待；目前确定的是 dispose 不及时返回，具体内部锁栈尚未进一步证明，不把推测写成已定位根因。若直接把该 dispose 接入连接回调，可能反过来阻塞取消通知。

Review 范围：新增本地传输测试、超时与 EOF 断言、合成数据边界、失败回收、执行计划状态和本记录。没有修改生产代码、依赖、密钥、全局 HttpTransportFactory 或现有成功测试。失败回归测试暂留工作区作为证据，不能提交为“步骤二完成”。文档及差异静态检查通过不等于运行门禁通过。

## 补齐方案与用户确认

在现有 agent 模块内实现 SDK 已提供的 HttpTransport 扩展，仅对 Chat 和 Intent 显式配置；沿用 JDK 21 HttpClient、现有 Reactor 和 AgentScope，不增加依赖，不更换模型 SDK。优先使用可取消的异步请求/响应体订阅，避免依赖阻塞 BufferedReader.close 才能结束传输；取消只释放当前请求，不关闭共享客户端。需覆盖 SSE 分片、UTF-8、正常结束、错误、首 Token 前后取消和请求隔离，并重新执行同等强度的传输测试。

该方案改变当前模型适配使用的传输实现，先请求决策；用户回复“同意”后恢复实施。未绕过暂停规则，也未退化为仅停止接收结果。

## 本次实现

- CancellableModelHttpTransport：Chat/Intent 显式注入，仅使用既有 JDK HttpClient 和 Reactor。流式响应由 JDK 非阻塞行订阅解析，取消同时停止当前 body subscription 和异步 request future，不关闭共享客户端。登记 SDK 既有退出清理机制，不替换全局默认 HttpTransport。
- SSE 解析覆盖 UTF-8 字节分片、CRLF、BOM、注释、空 data、多行 data、DONE 及末尾未带空行的事件；单事件限制 1 MiB 字符。该限制不等于 JDK 底层解码缓冲的字节上限，不能据此宣称通用抗超大行能力。
- 非流式意图请求通过可取消异步 HTTP future 等待，SDK 工作线程中断时取消本次 future。HTTP 错误不复制供应商错误正文。共享客户端只在 SDK 退出生命周期关闭。
- ChatExecutionControl 支持动作注册、迟注册立即执行、幂等注销、首次原因、专用 ChatCancellationException；动作在锁外执行且互相隔离。未提前添加步骤四的提交门闩。
- CancellableModelCall 显式捕获并取消真实订阅，再用专用异常终止等待；正常结束、异常及超时均注销动作。仅向另一条流发错误信号不足以证明原订阅被取消，首次单元测试发现该缺陷，已增加显式取消并保留断言。
- ChatModelPort 的三个在线回答入口与 IntentRecognitionPort 使用显式控制参数；Controller → ChatUseCase → Intent/Chat 使用同一个请求对象。兼容的离线入口创建独立控制，在线入口没有用默认方法丢弃参数。更新同步测试替身和原有 Mockito 签名，不降低原有安全断言。
- 工单 Agent 顶层执行可取消；CancellationAwareModel 在后续推理实际订阅前检查，工具入口读取工单前检查取消，Agent 在退出时释放。问候/知识/工单回答与意图识别关闭 SDK 隐式重试；独立结构化草稿保留既有重试策略，不添加 SSE 取消语义。
- IntentRecognitionService 将取消直接向上抛出，不走 SUPPORT_QUERY 故障降级。SSE sink 的取消通知移到队列锁外，避免模型取消动作持有发送队列锁。

## 回归与完整 Review

本地传输回归已经在实际模型适配器中通过：SDK 直连首 Token 前后、问候首 Token 前后、知识回答、非流式 Intent、工单 Agent，共 7 项使用五秒返回与服务端 EOF 断言。另有 4 项协议/并行 HTTP 测试覆盖正常解析、HTTP 500 无隐式重试、非流式意图、取消一条不影响另一在途请求，以及真实 ReActAgent 在工具执行期间取消后不再发起下一轮模型请求（请求计数为 1）。最后一项只用可控工单视图触发取消，没有替换 Agent 或伪造 HTTP 成功。

补充纯单元测试验证注册/取消竞争、注销、单动作故障隔离、真实订阅取消、超时兜底、工单模型/工具围栏、意图不降级和同一个连接控制贯穿用例。测试曾因 Intent 接口新增参数出现编译失败，更新测试替身后通过；未把编译失败算作运行通过。

2026-11-02 完整 Review 范围为本步骤全部新增/修改的应用端口、控制对象、异常、模型适配和传输、SSE 接线、测试及文档（包括未跟踪文件）；检查注册/取消竞争、真实订阅释放、共享客户端生命周期、Agent 工具围栏、正常/超时/故障语义、模型隐式重试、模块边界和日志安全。Review 修正了独立草稿重试兼容、HTTP 错误正文隔离、SSE BOM/空事件处理，以及网络故障与取消同时出现时优先保留取消原因。原测试更新只适配新增内部参数，保留原有输出安全、权限、重生成次数和协议断言。

修正后重新运行相关门禁，未发现本步骤剩余阻断问题。取消、模型不可用与完整业务清理仍有明确边界，不将步骤三至五未完成项虚报为完成。

| 最终命令/检查 | 结果与说明 |
|---|---|
| mvn -q test | 全量单元/接口测试通过；最终新增的取消竞争回归又在模型模块 Reactor 验证中执行 |
| mvn -q verify -Pintegration | 全量后端通过，覆盖 MySQL/Redis/ES、应用启动和本地 HTTP；不包含在线模型 |
| mvn -q -pl support-agent-agent -am verify -Pintegration | Review 修正及新增真实 Agent 工具边界回归后再次通过；最终有效 JUnit 报告汇总为 340 项单元/接口测试、73 项集成测试，失败/错误/跳过均为 0 |
| git diff --check、Markdown 链接与新增文件空白检查 | 通过；与运行测试分开记录 |

本步骤较步骤一新增 12 项纯单元测试和 11 项本地 HTTP 集成测试。完整 verify 在最终局部修正前通过，随后全量单元与受影响的 agent/application 模块 verify 再次通过；其他未改动模块沿用该次完整 verify 结果，不声称每次局部修正都重新执行所有容器测试。未修改前端，未执行前端门禁。Testcontainers 容器已自动回收；本地 HTTP/socket、执行器和 Agent 资源通过测试生命周期释放，未操作用户已有数据库或容器。

已同步当前系统基线、总计划、索引和相关 README；保留暂停时的失败证据，没有把原默认 SDK 写成已修复。仅显式配置的 Chat/Intent 使用新传输，其他模型能力没有被全局替换。

## 当前边界

尚未实施步骤三全面阶段检查、取消审计专用分类和独立清理治理，也未实施提交核实、重放版本调整及前端。当前取消异常仍经原用例失败路径收尾，不宣称完整取消治理已验收。

没有调用真实模型或读取 .env；本地服务均使用合成凭据。供应商是否停止推理和计费仍未知。停止在步骤二，等待步骤三指令；未提交、推送或进入下一步骤。
