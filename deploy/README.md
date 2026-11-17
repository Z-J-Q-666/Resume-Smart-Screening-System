# Docker 部署

一套 compose 拉起 7 个容器：MySQL / Redis / RocketMQ（namesrv + broker）/ AI 服务 / 后端 / 前端。

## 1. 准备

```bash
# 从模板生成 .env，填入自己的 LLM key
cp .env.docker.example .env
# 编辑 .env，至少把 LLM_API_KEY 换成真的
```

`.env` 已被 `.gitignore` 忽略，不会提交。**不填 key 会直接启动失败**——compose 里用了
`${LLM_API_KEY:?...}` 强制校验，避免带着空 key 跑起来再在运行时莫名其妙报 2002。

## 2. 启动

```bash
docker compose up -d --build
```

首次构建要下载 Maven 依赖、npm 包、pip 包，**大约 8–15 分钟**（取决于网络）。
之后再启动是秒级。

看进度：

```bash
docker compose ps
docker compose logs -f backend
```

## 3. 访问

| 地址 | 说明 |
|---|---|
| http://localhost:8088 | 前端（唯一必须暴露的端口） |
| http://localhost:8088/board | HR 候选人看板 |
| http://localhost:8088/apply/1 | 求职者投递页（需先在后台建岗位） |
| http://localhost:8080/api/stats/ai | AI 调用统计（直连后端，调试用） |

MySQL 3306 / Redis 6379 / RocketMQ 9876、10911 也映射到了宿主机，方便本地用客户端排查。
**服务器部署时应该把这些 ports 删掉**，只留 `WEB_PORT`。

## 4. 和本地开发的三处差异

这几处不改会直接连不上，是容器部署最容易踩的坑：

| 项 | 本地 | 容器 |
|---|---|---|
| 数据库地址 | `127.0.0.1:3306` | `mysql:3306`（服务名） |
| AI 服务地址 | `127.0.0.1:8000` | `http://ai-service:8000` |
| 上传目录 | `backend/uploads` | 挂卷到 `/data/uploads` |

配置全部通过 `application.yml` 里的 `${DB_URL:...}` 这类占位符注入，
**没有依赖 Spring 的 relaxed binding**——`app.ai.base-url` 这种带连字符的键在环境变量里
映射规则很隐晦，写死 `${AI_BASE_URL:...}` 更不容易出错。

另外 `ai-service/run.py` 里 `host` 写死 `127.0.0.1`，容器里外部访问不到，
所以 AI 镜像的 `CMD` 直接走 `uvicorn --host 0.0.0.0`，不用 `run.py`。

## 5. 数据

| 卷 | 内容 | 备份 |
|---|---|---|
| `mysql-data` | 全部业务数据 | `docker exec rs-mysql mysqldump -uroot -p*** resume_screening > backup.sql` |
| `rocketmq-store` | MQ 消息持久化，重启不丢任务 | 一般不用备 |
| `upload-data` | 简历 PDF 原件 | `docker cp rs-backend:/data/uploads ./uploads-backup` |

**建表脚本只跑一次**：`sql/schema.sql` 挂在 `/docker-entrypoint-initdb.d/`，
只有数据目录为空时才执行。已经跑过之后再改表结构，需要手工执行迁移脚本，例如
`sql/migration_2026-11-15-ai-observability.sql`。

彻底重来：

```bash
docker compose down -v     # -v 会删掉所有卷，数据一起没
```

## 6. 常见问题

**镜像拉不动**

`maven` / `eclipse-temurin` / `node` / `nginx` 都来自 Docker Hub。国内建议配加速器
（Docker Desktop → Settings → Docker Engine 里加 `registry-mirrors`）。

**broker 起来了但投递一直卡在 SCORING**

先看 broker 是否注册成功：

```bash
docker compose logs rocketmq-broker | grep -i "boot success"
```

`deploy/rocketmq/broker.conf` 里的 `brokerIP1 = rocketmq-broker` 必须是 compose 网络内可解析的名字。
如果改成了 IP，容器重建后会失效。

**后端启动报连不上 MySQL**

后端依赖 `mysql` 的 healthcheck，正常不会抢跑。如果反复失败，
先 `docker compose logs mysql` 看是不是首次初始化太慢（`start_period` 20 秒可能不够慢机器）。

**端口冲突**

`.env` 里改 `WEB_PORT` / `MYSQL_PORT` / `REDIS_PORT` 即可，不用动 compose 文件。

## 7. 只想起一部分

```bash
# 只起基础设施，后端仍在 IDEA 里调试
docker compose up -d mysql redis rocketmq-namesrv rocketmq-broker ai-service
```

这时后端用本地 `application.yml` 的默认值（127.0.0.1）就能连上，
因为基础设施的端口都映射到了宿主机。

---

> 状态说明：本文档的编排已通过 `docker compose config` 校验（服务、依赖、变量插值均正确），
> 但**容器尚未实际跑起来验证**——本机 Docker Desktop 的 daemon 未能启动。
> 首次 `up` 之后如果遇到问题，优先看 `docker compose logs <service>`。
