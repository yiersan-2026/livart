# livart Backend

Spring Boot + Spring Security JWT + MyBatis Plus 后端，用 RabbitMQ 串行化画布保存请求，用 PostgreSQL 保存项目画布状态，用 MinIO 保存画布图片资源。

本后端是 livart 在原开源前端画布项目基础上新增的永久画布服务层，负责账号登录、JWT 鉴权、项目管理、画布状态保存、图片资源上传和异步保存队列。

## 接口

- `GET /api/health`：健康检查
- `POST /api/auth/register`：注册账号并返回 JWT
- `POST /api/auth/login`：登录账号并返回 JWT
- `GET /api/auth/me`：读取当前登录用户
- `POST /api/auth/logout`：退出登录（前端清理 JWT）
- `GET /api/canvases`：项目列表，一个项目对应一张画布
- `POST /api/canvases`：创建项目画布
- `GET /api/canvases/{id}`：读取指定项目画布
- `PUT /api/canvases/{id}`：把指定项目画布保存请求放入 RabbitMQ 队列
- `GET /api/canvas/current`：读取默认永久画布
- `PUT /api/canvas/current`：把画布保存请求放入 RabbitMQ 队列
- `POST /api/assets`：上传图片资源到 MinIO
- `GET /api/assets/{id}/content`：读取图片资源内容

除健康检查、注册、登录和图片内容读取外，业务接口需要携带 `Authorization: Bearer <token>`。画布项目和上传资源会按登录用户隔离。

## 本地启动

```bash
cp .env.example .env
# 填入 PostgreSQL、MinIO、RabbitMQ 和 JWT_SECRET 配置
set -a
source .env
set +a
mvn spring-boot:run
```

前端开发环境会把 `/api/auth`、`/api/canvases`、`/api/canvas`、`/api/assets` 和 `/api/health` 代理到 `http://localhost:8080`。
