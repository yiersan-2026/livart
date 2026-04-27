# livart Backend

Spring Boot + Spring Security JWT + MyBatis Plus 后端，用 RabbitMQ 串行化画布保存请求，用 PostgreSQL 保存项目画布状态和 pgvector 系统知识库，用 MinIO 保存画布图片资源，并通过统一 Agent 入口执行文生图、图生图、局部重绘、删除物体和去背景请求，同时提供图片交付包打包下载。

本后端是 livart 在原开源前端画布项目基础上新增的永久画布服务层，负责账号登录、JWT 鉴权、用户中转站配置、项目管理、画布状态保存、图片资源上传、异步保存队列、WebSocket 生图状态推送、系统知识库检索和 AI Agent 执行。前端不再直接调用文生图/图生图提交接口，而是统一提交到 Agent，由 Agent 判断回答、拒绝或执行图片任务。系统功能问答会先检索 PostgreSQL 知识库，pgvector 可用时走向量检索，不可用时退回关键词检索；提示词优化不再暴露单独接口，而是在后端调用上游文生图/图生图接口前自动执行；`gpt-image-2` 文生图会按画幅默认注入 2K `size`；删除物体会使用专门的 `image-remover` 模式，避免普通重绘提示词影响局部删除；去背景会使用 `background-removal` 模式，先识别图片主要主体，再只保留主体并把主体以外区域替换为纯白色背景。异步生图任务会在 job 响应和 WebSocket 推送中返回 `originalPrompt` 与 `optimizedPrompt`，前端会随图片节点一起保存。

## 接口

- `GET /api/health`：健康检查
- `POST /api/auth/register`：注册账号并返回 JWT
- `POST /api/auth/login`：登录账号并返回 JWT
- `GET /api/auth/me`：读取当前登录用户
- `POST /api/auth/logout`：退出登录（前端清理 JWT）
- `GET /api/user/config`：读取当前用户的中转站配置
- `PUT /api/user/config`：保存当前用户的中转站配置
- `GET /api/canvases`：项目列表，一个项目对应一张画布
- `POST /api/canvases`：创建项目画布
- `GET /api/canvases/{id}`：读取指定项目画布
- `PUT /api/canvases/{id}`：把指定项目画布保存请求放入 RabbitMQ 队列
- `GET /api/canvas/current`：读取默认永久画布
- `PUT /api/canvas/current`：把画布保存请求放入 RabbitMQ 队列
- `POST /api/assets`：上传图片资源到 MinIO
- `GET /api/assets/{id}/content`：读取图片资源内容
- `GET /api/assets/{id}/preview`：读取画布显示用预览图，缺失时回退原图
- `GET /api/assets/{id}/thumbnail`：读取侧栏/选择器缩略图，缺失时回退原图
- `POST /api/agent/runs`：统一 Agent 执行入口，自动规划并创建文生图/图生图异步任务
- `GET /api/image-jobs/{jobId}`：读取图片任务状态（WebSocket 不可用时兜底）
- `WS /ws/image-jobs`：推送当前用户的图片任务状态，连接后先发送 `auth` 消息携带 JWT
- `POST /api/exports/images`：按前端传入的图片资源列表生成 ZIP 下载包
- `GET /api/exports/{exportId}/download`：下载当前用户生成的 ZIP 包

除健康检查、注册、登录和图片内容读取外，业务接口需要携带 `Authorization: Bearer <token>`。中转站配置、画布项目和上传资源会按登录用户隔离。

## 本地启动

```bash
cp .env.example .env
# 填入 PostgreSQL、MinIO、RabbitMQ 和 JWT_SECRET 配置
set -a
source .env
set +a
mvn spring-boot:run
```

前端开发环境会把 `/api/auth`、`/api/user`、`/api/canvases`、`/api/canvas`、`/api/assets`、`/api/agent`、`/api/image-jobs`、`/api/health` 和 `/ws/image-jobs` 代理到 `http://localhost:8080`。

## 生图分辨率

文生图默认对 `gpt-image-2` 注入真实 2K `size`，并按提示词里的画幅要求映射为：`1:1=2048x2048`、`16:9=2048x1152`、`9:16=1152x2048`、`4:3=2048x1536`、`3:4=1536x2048`。当前中转站实测单纯在提示词写“4K、超高清、高分辨率”仍只返回 1024，`4096x4096` 会返回 502，因此默认不开真 4K。

可用环境变量调整：

- `LIVART_DEFAULT_IMAGE_SIZE_ENABLED=false`：关闭默认 `size` 注入。
- `LIVART_DEFAULT_IMAGE_LONG_SIDE=1024/2048`：调整默认长边，默认 `2048`。
- `LIVART_IMAGE_JOB_WORKER_COUNT=16`：图片任务并发 worker 数，默认 `16`；如果上游限流或 502/504 增多，可以降到 `8` 或 `12`。
- `LIVART_KNOWLEDGE_EMBEDDING_BASE_URL=https://api.siliconflow.cn/v1`：系统知识库 embedding 网关，默认使用硅基流动。
- `LIVART_KNOWLEDGE_EMBEDDING_API_KEY`：系统知识库 embedding API Key，必填；缺失或接口失败会直接报错，不再使用本地向量兜底。
- `LIVART_KNOWLEDGE_EMBEDDING_MODEL=BAAI/bge-m3`：系统知识库向量模型，默认 1024 维。
- `LIVART_KNOWLEDGE_AUTO_INDEX_ENABLED=false`：关闭启动时自动索引内置知识文档。
