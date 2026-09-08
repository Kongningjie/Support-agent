# MySQL 连接排查

确认配置项 `spring.datasource.url` 指向正确地址。

```powershell
Test-NetConnection localhost -Port 3306
```
