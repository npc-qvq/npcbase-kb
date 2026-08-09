# NPC Base Knowledge Base 部署手册

本文档记录 NPC Base Knowledge Base 在服务器上的部署和更新流程。

## 1. 部署结构

服务器上的固定目录：

```text
/data/npcbase/
├── .env                              # 服务器私有环境变量，不提交 Git
├── nginx/sites/npcbase.conf          # 宿主机 Nginx 配置
└── data/kb/                          # Git 项目目录
    ├── web/dist/                     # 前端构建产物，由 Nginx 直接提供
    ├── backend/target/               # 后端 Maven 构建产物
    └── deploy/docker-compose.yml     # 后端容器编排
```

线上地址：

```text
https://kb.npcbase.cloud
```

服务关系：

```text
浏览器
  └── Nginx :80/:443
      ├── 静态前端: /data/npcbase/data/kb/web/dist
      └── /api/* -> 127.0.0.1:9527
                         └── kb-service 容器
                             ├── MySQL
                             ├── Redis
                             └── Qdrant
```

## 2. 服务器前置条件

确认以下命令可用：

```bash
node -v
npm -v
java -version
mvn -version
docker --version
docker compose version
nginx -v
```

后端容器与已有基础设施共用 Docker 网络 `npcbase_default`。不要在部署知识库时删除或重建 MySQL、Redis、Qdrant 容器。

## 3. 环境变量

所有线上密钥统一放在：

```text
/data/npcbase/.env
```

`deploy/docker-compose.yml` 通过绝对路径加载该文件：

```yaml
env_file:
  - /data/npcbase/.env
```

不要把真实密码、模型 API Key、访问密钥或 `KB_ACCESS_KEY_HASH` 提交到 Git。

PBKDF2 哈希中包含 `$` 时，必须使用单引号包裹完整值，避免 Docker Compose 把 `$xxx` 解析成环境变量：

```env
KB_ACCESS_KEY_HASH='pbkdf2$210000$盐值$摘要'
```

修改 `.env` 后必须重新创建后端容器，普通 `restart` 不会重新加载环境变量：

```bash
cd /data/npcbase/data/kb
docker compose -f deploy/docker-compose.yml up -d --force-recreate kb-service
```

## 4. 首次部署

如果项目目录还没有代码：

```bash
sudo chown -R ubuntu:ubuntu /data/npcbase/data/kb

sudo -u ubuntu git clone --branch main --single-branch \
  https://github.com/npc-qvq/npcbase-kb.git \
  /data/npcbase/data/kb
```

安装依赖并构建：

```bash
cd /data/npcbase/data/kb/web
sudo -u ubuntu npm ci
sudo -u ubuntu npm run build

cd /data/npcbase/data/kb/backend
sudo -u ubuntu mvn clean package -DskipTests
```

启动后端容器：

```bash
cd /data/npcbase/data/kb
docker compose -f deploy/docker-compose.yml config -q
docker compose -f deploy/docker-compose.yml up -d --build --force-recreate kb-service
```

## 5. 日常更新流程

代码推送到 GitHub 的 `main` 分支后，在服务器执行以下流程：

### 5.1 拉取代码

```bash
cd /data/npcbase/data/kb
sudo -u ubuntu git pull --ff-only origin main
```

`--ff-only` 可以避免服务器自动生成不必要的合并提交。如果提示有本地修改，先停止操作并检查：

```bash
sudo -u ubuntu git status --short
```

### 5.2 更新前端

```bash
cd /data/npcbase/data/kb/web
sudo -u ubuntu npm ci
sudo -u ubuntu npm run build
```

前端构建完成后，Nginx 会直接使用新的 `web/dist` 文件。仅修改前端时不需要重建后端容器。

### 5.3 更新后端

如果修改了 `backend` 代码或后端依赖：

```bash
cd /data/npcbase/data/kb/backend
sudo -u ubuntu mvn clean package -DskipTests
```

然后重建并替换后端容器：

```bash
cd /data/npcbase/data/kb
docker compose -f deploy/docker-compose.yml config -q
docker compose -f deploy/docker-compose.yml up -d --build --force-recreate kb-service
```

### 5.4 验证服务

```bash
cd /data/npcbase/data/kb
docker compose -f deploy/docker-compose.yml ps
curl -fsS https://kb.npcbase.cloud/api/health
docker compose -f deploy/docker-compose.yml logs --tail=100 kb-service
```

成功标准：

- 容器状态为 `Up`
- `/api/health` 返回 HTTP `200`
- JSON 中包含 `"status":"UP"`
- 日志中包含 `Started KbApplication`
- 日志不持续出现数据库、Redis 或 Qdrant 连接错误

## 6. 一键部署脚本

仓库提供 `deploy/update.sh`，用于自动拉取 `main`、按改动范围构建应用、更新后端容器并执行健康检查。

第一次把脚本推送到 GitHub 后，服务器需要手动拉取一次：

```bash
cd /data/npcbase/data/kb
sudo -u ubuntu git pull --ff-only origin main
```

由于第一次运行脚本前已经拉取了提交，需要使用 `--force` 完成全量构建：

```bash
sudo bash /data/npcbase/data/kb/deploy/update.sh --force
```

以后每次代码推送到 `main` 后，只需要执行：

```bash
sudo bash /data/npcbase/data/kb/deploy/update.sh
```

脚本行为：

- 使用文件锁防止同一时间重复部署
- 拒绝覆盖服务器上的 Git 已跟踪修改
- 使用 `git pull --ff-only`，不在服务器自动创建合并提交
- 只在 `web/` 变化时构建前端
- 只在 `backend/`、Dockerfile 或 Compose 配置变化时构建并重启后端
- 不操作 MySQL、Redis、Qdrant 和 Docker 数据卷
- 最多等待约一分钟检查后端健康状态
- 部署失败时输出最近 120 行后端日志

环境变量、服务器配置或构建产物需要强制重新部署时执行：

```bash
sudo bash /data/npcbase/data/kb/deploy/update.sh --force
```

脚本支持通过环境变量覆盖默认设置，例如：

```bash
KB_DEPLOY_BRANCH=main \
KB_DEPLOY_HEALTH_ATTEMPTS=45 \
sudo -E bash /data/npcbase/data/kb/deploy/update.sh
```

## 7. Nginx 与 HTTPS

Nginx 配置文件：

```text
/data/npcbase/nginx/sites/npcbase.conf
```

修改 Nginx 后始终先检查语法，再重新加载：

```bash
sudo nginx -t
sudo systemctl reload nginx
```

知识库证书路径：

```text
/etc/letsencrypt/live/kb.npcbase.cloud/fullchain.pem
/etc/letsencrypt/live/kb.npcbase.cloud/privkey.pem
```

证书续期测试：

```bash
sudo certbot renew --dry-run
```

只有 Nginx 配置发生变化时才需要 reload。前端重新构建不需要 reload Nginx。

## 8. 常见问题

### `git pull` 提示 dubious ownership

确保项目目录属于 `ubuntu`：

```bash
sudo chown -R ubuntu:ubuntu /data/npcbase/data/kb
```

之后使用 `sudo -u ubuntu git ...` 执行 Git 操作。

### Compose 提示变量未设置，变量名来自密钥片段

通常是 `.env` 中的 PBKDF2 哈希包含未转义的 `$`。将完整哈希改为单引号形式：

```env
KB_ACCESS_KEY_HASH='pbkdf2$210000$盐值$摘要'
```

然后重新创建容器：

```bash
docker compose -f deploy/docker-compose.yml up -d --force-recreate kb-service
```

### `curl` 返回 `Connection reset by peer`

先等待应用完成启动，再查看日志末尾：

```bash
sleep 10
docker compose -f deploy/docker-compose.yml ps -a
docker compose -f deploy/docker-compose.yml logs --tail=200 kb-service
```

重点查找最后的 `ERROR` 或 `Caused by`。容器显示 `Started` 不等于 Spring Boot 已经完成启动。

### MySQL `Access denied`

确认容器实际加载的数据源地址（不会显示密码）：

```bash
docker inspect deploy-kb-service-1 \
  --format '{{range .Config.Env}}{{println .}}{{end}}' \
  | grep '^SPRING_DATASOURCE_URL='
```

如果地址正确但仍被拒绝，需要给对应 MySQL 用户授权目标数据库，或把数据源改回该用户已有权限的数据库。

## 9. 安全与数据保护

以下命令禁止在生产服务器上执行：

```bash
docker compose down -v
docker system prune --volumes
git reset --hard
```

它们可能删除数据库卷、运行数据或服务器上的本地修改。更新前应保留当前 Nginx 配置备份，并确保 `/data/npcbase/.env` 不在 Git 项目目录中。
