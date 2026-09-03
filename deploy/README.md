# 本地基础设施

本目录提供阶段 1 冻结的本地数据基线：MySQL 8.4.11、Redis 8.8.0、
Elasticsearch 9.5.2 和与 Elasticsearch 同版本的 ICU 分词插件。所有服务端口只绑定
到 `127.0.0.1`，不得直接用于生产部署。

```powershell
Copy-Item .\.env.example .\.env
# 编辑 .env，为 SUPPORT_AGENT_MYSQL_PASSWORD 和 SUPPORT_AGENT_MYSQL_ROOT_PASSWORD 设置仅限本机的值。
docker compose -f .\deploy\compose.yml up -d --build
docker compose -f .\deploy\compose.yml ps
docker compose -f .\deploy\compose.yml down
```

复制命令创建不会纳入 Git 的本地变量文件；必须先填写两个 MySQL 密码变量。随后三条
Docker 命令分别启动基础设施、查看健康状态以及停止容器但保留命名数据卷。删除数据卷
会清空本地数据，必须由用户明确确认后单独执行。

本地应用默认连接 `localhost:3306`、`localhost:6379` 和 `localhost:9200`。如果端口被
占用，可在当前 PowerShell 会话设置 `SUPPORT_AGENT_MYSQL_PORT`、
`SUPPORT_AGENT_REDIS_PORT` 或 `SUPPORT_AGENT_ELASTICSEARCH_PORT` 后启动 Compose，
并同步修改对应的应用连接地址。完整配置项及字段含义见项目根目录 `.env.example` 和
实施方案文档。
