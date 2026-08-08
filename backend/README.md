# NPC Base Knowledge Base Backend

Spring Boot 3 / Java 17 后端服务，默认监听 `9527` 端口。

## 本地运行

```powershell
Copy-Item .env.example .env
# 编辑 .env，填入 MySQL、Redis、Qdrant、模型与访问控制配置
mvn spring-boot:run
```

健康检查地址：`http://localhost:9527/api/health`。

## 配置原则

- `backend/.env` 是本地与服务器私有配置，已被 Git 忽略。
- `backend/.env.example` 仅提供通用占位模板，不能填写真实密码或 API Key。
- 数据库地址、Qdrant、Embedding、Rerank 和聊天模型均通过环境变量配置。
- 唯一访问密钥仅以 PBKDF2 哈希保存在私有环境变量中，不能把明文写入前端或仓库。
- 线上 HTTPS 部署必须设置 `KB_ACCESS_COOKIE_SECURE=true`，并配置固定的 `KB_PUBLIC_DEMO_CONVERSATION_ID`。
- 只有在可信反向代理会覆盖客户端传入请求头时，才设置 `KB_ACCESS_TRUST_FORWARDED_FOR=true`。
- Docker 运行时容器内资料目录默认是 `/data/npcbase/data/kb`，可通过 `KB_STORAGE_ROOT` 调整。

完整项目说明参见仓库根目录的 [README](../README.md)。
