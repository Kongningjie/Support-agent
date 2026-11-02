# 聊天断流重试与结果恢复记录

日期：2026-11-02。状态：已完成，完整 Review 通过。执行聊天断连取消单批次计划步骤五，不是重新执行 F5。当前只调整聊天 store、流客户端及页面既有动作，不新增业务页或后端外部契约。

范围：中断结果未知提示、用户显式复用消息 ID 重试、清除临时正文/引用/建议、忙和版本恢复保留消息、读取服务端事实以及真实后端 Playwright 验证。禁止浏览器拦截伪造成功，不调用真实模型；后端联调采用测试源集内的本地合成上游。

## 变更

- abort、网络中断和未收到完成事件统一提示结果待确认；取消后不宣称服务端未保存，不自动重连或重试。
- 显式重试复用 clientMessageId，清除临时正文、引用、建议和完成信息；忙响应保留消息，版本冲突读取详情后仍保留待确认消息。服务端已保存轮次按 runId 合并，旧轮次重放不得降低当前版本。
- 增加既有页面“重新加载会话”动作；代际改变禁止旧消息重试；建议操作只对成功消息显示。退出读取时释放响应流，忽略已取消/已切换请求的迟到回调。

## 测试与 Review

`npm run format:check`、`npm run lint`、`npm run type-check`、`npm run test:unit`、`npm run build` 均通过；单元测试 79 项、20 个文件。

`npm run test:e2e -- tests/e2e/chat-cancellation.e2e.spec.ts` 最终 1 项通过（33.9 秒，运行总计 36.3 秒）。使用测试源集 ChatBrowserTestServer、临时真实 MySQL/Redis、真实 Spring 登录/SSE 和本地合成模型 HTTP 上游，没有 Playwright route 拦截。覆盖取消后忙响应、原 ID 显式重试、真实提交但完成响应丢失、旧版本重放、页面正文不重复；上游实际请求数 3。

启动测试服务器需先用 Maven test-compile 和 dependency:build-classpath 生成测试 classpath，将各模块 target/classes 和 bootstrap target/test-classes 加入 Java classpath，传入 ready、stop、model-state 三个临时文件路径。服务器就绪后，将 VITE_DEV_PROXY_TARGET 指向 ready 中的端口，E2E_BASE_URL 指向本地 Vite，E2E_ADMIN_USERNAME/PASSWORD 使用测试源集中的合成管理员，E2E_CHAT_MODEL_STATE 指向 model-state。测试完成在 finally 中创建 stop 文件。仅限测试源集启动，不是生产运行配置。

Review 覆盖全部前端差异和浏览器夹具；修正旧版本回退、登录等待竞态、失败清理遮盖异常。代理检测断连约需两个心跳，专项等待上限 35 秒，全局测试超时未调整；后端已检测取消后的 5 秒门禁不变。最终 PASS 标记及临时服务/容器退出已核实，未访问真实模型或 .env。已有 el-select/el-option 警告仍存在，本批次未扩展修复。

后端测试、完整 Review、指标和边界见[主工作记录](../../work-logs/2026-11-02-chat-replay-step-five.md)。不重写 F5 历史验收，也不声称执行了既有在线 E2E。未提交或推送。

## 后续授权联调增量

用户随后授权真实联调及通过后推送。Chat 适配层 3 项真实模型测试通过（含取消和下一次请求恢复），浏览器仍采用本地合成上游专项，不声称全套在线 E2E 已执行。

修复聊天反馈区 el-select/el-option 未注册：直接导入导致现有 Option 类型定义检查失败，最终沿用仓库已有原生 select/option 方式，保留标签绑定、禁用、帮助程度及原因选择。浏览器专项新增组件解析警告为空的断言；不关闭类型检查、不增加依赖。完整差异再次 Review，最终前端门禁和浏览器复测结果见主工作记录补记。
