# NPC Base Knowledge Base Backend

Spring Boot 3 / Java 17 后端服务，默认监听 `9527` 端口。

## 本地运行

```powershell
Copy-Item .env.example .env
# 编辑 .env，填入 MySQL、Qdrant、Embedding 和可选的 DeepSeek 配置
mvn spring-boot:run
```

健康检查地址：`http://localhost:9527/api/health`。

## 配置原则

- `backend/.env` 是本地与服务器私有配置，已被 Git 忽略。
- `backend/.env.example` 仅提供通用占位模板，不能填写真实密码或 API Key。
- 数据库地址、Qdrant、Embedding、Rerank 和聊天模型均通过环境变量配置。
- Docker 运行时容器内资料目录默认是 `/data/npcbase/data/kb`，可通过 `KB_STORAGE_ROOT` 调整。

完整项目说明参见仓库根目录的 [README](../README.md)。
