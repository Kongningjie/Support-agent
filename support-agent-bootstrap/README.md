# support-agent-bootstrap

唯一可执行的 Spring Boot 启动与模块装配层。

当前负责配置属性绑定、六模块 Bean 装配、Profile、健康检查、安全配置校验和应用启动集成测试，不承载领域规则。构建与运行：

```powershell
mvn -pl support-agent-bootstrap -am package
java -jar .\support-agent-bootstrap\target\support-agent-bootstrap-0.1.0-SNAPSHOT.jar
```
