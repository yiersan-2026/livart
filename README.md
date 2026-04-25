<div align="center">

# livart

**无限画布 AI 图像创作工作台**

![livart 真实界面截图](./docs/screenshots/livart-canvas.png)

</div>

---

## 项目简介

livart 是一款基于 **无限画布** 的 AI 图像创作工具。它把文生图、图生图、局部重绘、画幅控制、图片引用、拖拽素材、项目画布、历史记录和一键 Docker 部署整合到一个自由画布中，适合用视觉布局表达创作意图：用户可以在画布里拖入多张图片，用 `@` 引用图片，圈选局部区域，再用一句话描述“把这张图里的鞋子放到那张图的桌子上”这类跨图编辑需求。

本项目基于上一个开源系统 [yiqi-software/ArtisanLab](https://gitee.com/yiqi-software/ArtisanLab) 二次开发而来。原项目提供了无限画布、基础 AI 图像生成、画布元素编辑和视觉逻辑工作流能力；livart 在此基础上补充了更完整的图生图、局部重绘、永久画布、账号体系、后端持久化和 Lovart 风格交互。

当前开源地址：[sunowen/livart](https://gitee.com/sunowen/livart.git)

## 本版本新增能力

- **Docker 一键部署**：提供多阶段 `Dockerfile` 和默认 `docker-compose.yml`，可同时启动 livart、PostgreSQL、RabbitMQ 和 MinIO。
- **独立生图接口配置**：文生图、图生图和提示词优化统一由后端代理，默认支持 OpenAI Images API 兼容格式，API Key 按用户隔离保存。
- **图生图与局部重绘**：选中图片后可直接输入指令重绘，也可进入“局部”模式涂抹区域并通过 `mask` 精确修改局部。
- **非破坏式图片重绘**：直接重绘或局部重绘会在原图右侧创建新图片节点，并用连线保留“原图 → 新图”的派生关系。
- **画幅比例选择**：文生图、图生图和局部重绘支持 `自动`、`1:1`、`4:3`、`3:4`、`16:9`、`9:16`。
- **自适应图片框**：生成或重绘完成后会读取真实图片尺寸，画布图片框自动匹配图片比例，不再强制正方形。
- **原图/预览图分层**：画布和图片选择器默认加载压缩预览图，AI 重绘与高清导出仍保留原始图片链接，减少大图造成的浏览器内存压力。
- **WebSocket 生图状态推送**：生图任务仍由 REST 提交，运行、完成和失败状态通过 `/ws/image-jobs` 实时推送；刷新页面后会重新订阅未完成任务。
- **内联提示词自动优化**：前端不再单独调用优化接口；后端会在真正请求上游文生图/图生图接口前，结合图片角色分析自动优化提示词，再把优化后的 `prompt` 发给上游。
- **Lovart 风格图片引用**：右侧输入框支持输入 `@` 选择画布图片，并以可删除的内联标签参与提示词上下文。
- **图片拖入画布**：支持直接把本机图片拖进画布，自动创建图片节点。
- **选中图片下方对话框**：选中单张图片后显示 3 行输入框，可用 `Ctrl/⌘ + Enter` 快速提交。
- **并行图片处理**：多个图片的重绘状态互不阻塞，切换图片不会清空各自输入内容。
- **永久画布后端**：新增 Spring Boot + MyBatis Plus 后端，用 PostgreSQL 保存画布项目，用 MinIO 保存图片资源。
- **Spring Security JWT 登录**：支持账号注册、登录和 30 天 JWT，会话清理后重新登录即可找回账号下的项目画布。
- **异步保存队列**：画布保存请求进入 RabbitMQ，后端单消费者按顺序落库，并通过 revision 避免旧数据覆盖新数据。
- **多项目画布**：支持创建多个项目，一个项目对应一张独立画布，并记住最近打开的项目。
- **画布体验优化**：缩放逻辑改为以鼠标位置为锚点，输入框滚动、键盘快捷键冲突和重启后临时加载状态残留已修复。

## 核心功能

### 无限画布

- 自由缩放（10% - 500%）
- 无边界平移
- 多元素自由布局
- 框选批量操作
- 图层层级管理
- 以鼠标位置为中心的缩放体验
- 本机图片拖拽导入

### 视觉逻辑推理

livart 会尝试理解画布上的视觉语义：

| 视觉布局 | AI 理解 |
|---------|--------|
| A → B（箭头指向） | 将 A 的属性应用到 B |
| 人物 + 衣服（并列） | 执行换装操作 |
| 涂鸦线条 | 特效、路径或区域标记 |

### 局部重绘

选中图片后点击下方输入框里的“局部”按钮，即可使用画笔涂抹需要修改的区域。提交后系统会把涂抹区域转换为 Images API 的 `mask` 参数，只重绘被涂抹的区域，未涂抹区域尽量保持原图不变。

重绘不会覆盖原图，而是在原图右侧生成一个新的图片节点。新图会记录父级图片和提示词，画布上会用虚线箭头展示派生关系，方便持续迭代不同版本。

### 画幅与图片框

生图和重绘入口都支持选择画幅：

| 选项 | 说明 |
|------|------|
| 自动 | 文生图使用模型默认画幅；图生图和局部重绘沿用参考图比例 |
| 1:1 | 方图 |
| 4:3 / 3:4 | 横向或竖向标准画幅 |
| 16:9 / 9:16 | 横向宽屏或竖向手机画幅 |

生成完成后，livart 会按返回图片的真实宽高调整画布图片框；如果上游模型实际返回的比例和请求比例不同，画布仍以真实图片形状为准。

为了让无限画布在多张大图时保持流畅，后端会在图片上传到 MinIO 时额外生成最长边约 `1600px` 的预览图和最长边约 `512px` 的缩略图。画布渲染和侧栏图片选择优先使用预览/缩略图；原始图片 URL 会继续保存在画布状态里，用于 AI 图生图、局部重绘和后续高清处理。

### 永久画布项目

livart 支持创建多个项目画布。前端会把画布元素、消息、缩放位置和图片资源保存到后端：

- PostgreSQL：保存项目、画布状态和快照记录
- MinIO：保存画布中的图片资源
- RabbitMQ：串行化保存请求，减少并发保存冲突
- 用户系统：项目和上传资源按登录账号隔离，清理浏览器缓存后可通过账号重新加载历史记录

## 项目结构

```text
frontend/  React + Vite 前端
backend/   Spring Boot + MyBatis Plus 后端
docs/      项目说明和真实截图
```

## 快速开始

### 环境要求

- 普通使用者：只需要安装 Docker Desktop
- 开发者本地调试：Node.js 18+、Java 17+、PostgreSQL、MinIO、RabbitMQ
- 生图能力：支持 OpenAI Images API 兼容接口或 Gemini 图像接口的网络环境

### Docker 一键部署

项目根目录提供了多阶段 `Dockerfile` 和自包含的 `docker-compose.yml`：Compose 会同时启动 livart、PostgreSQL、RabbitMQ 和 MinIO。浏览器访问同一个 Spring Boot 服务即可，`/api/images/generations`、`/api/images/edits` 和 `/api/image-jobs/*` 都由后端代理；提示词优化已经内置在后端生图链路中，生图任务状态通过同域 `/ws/image-jobs` 推送。

先打开 Docker Desktop，等它显示 Docker 正在运行。没有编程经验也可以直接执行这一条命令：

```bash
docker compose up -d --build
```

启动后访问：

- livart：`http://localhost:8080`
- MinIO 控制台：`http://localhost:9001`，默认只绑定本机 `127.0.0.1`

进入 livart 后先注册一个账号；第一次生图时，在页面配置 AI 中转站 Base URL、API Key、生图模型和对话模型即可。后续项目、图片和配置都会自动保存。

如果要改端口或用于公网部署，建议先创建 `.env` 覆盖默认口令：

```bash
cp .env.example .env
# 按需修改 .env 后启动
docker compose up -d --build
```

不要把包含数据库密码、MinIO 密钥、RabbitMQ 密码或 AI API Key 的 `.env` 文件提交到仓库。AI 中转站 Base URL、API Key、生图模型和对话模型仍然在用户首次登录后通过页面填写，并按用户保存到数据库。

应用日志默认写入容器内 `/tmp/livart-logs/livart-backend.log`，按天滚动并默认只保留最近 1 天；Docker stdout 日志也限制为单个 10MB 文件，避免长期运行撑满磁盘。如需调整，可在 `.env` 中设置 `LOG_PATH`、`LOG_MAX_HISTORY`、`LOG_TOTAL_SIZE_CAP`。

### 启动后端

```bash
cd backend
cp .env.example .env
# 填入 PostgreSQL、MinIO、RabbitMQ、JWT_SECRET 等配置
set -a
source .env
set +a
mvn spring-boot:run
```

### 启动前端

```bash
cd frontend
npm install
npm run dev
```

前端开发服务器会把 `/api/auth`、`/api/user`、`/api/canvases`、`/api/canvas`、`/api/assets`、`/api/images`、`/api/image-jobs`、`/api/health` 和 `/ws/image-jobs` 代理到 `http://localhost:8080`。

首次进入页面需要注册或登录账号。登录成功后，前端会把 JWT 保存在浏览器本地；如果浏览器缓存被清理，只要重新登录同一个账号，就会从后端加载该账号下的项目画布历史。

### API 配置

首次登录后会自动弹出中转站配置，之后也可以点击界面左上角的设置图标修改。配置会保存到后端数据库，并按登录用户隔离，不同用户可以使用不同的中转站和模型。只需要填入：

- 中转站 Base URL，例如 `https://example.com/v1/`
- API Key
- 生图模型，下拉框目前固定为 `gpt-image-2`
- 对话模型，下拉框支持 `gpt-5.5` 和 `gpt-5.4`

系统会自动拼接：

- 文生图：`{Base URL}/images/generations`
- 图生图：`{Base URL}/images/edits`
- 对话：`{Base URL}/responses`，如果接口不可用会直接报错

也可以在 `frontend/.env.local` 中预填默认值，用户保存后仍以数据库中的个人配置为准：

```bash
IMAGE_API_BASE_URL=https://example.com/v1/
IMAGE_API_MODEL=gpt-image-2
IMAGE_API_KEY=your-api-key
PROMPT_OPTIMIZER_MODEL=gpt-5.5
```

不要把真实 `frontend/.env.local`、`backend/.env` 或任何 API Key 提交到仓库。用户在界面里保存的 API Key 会写入后端数据库的个人配置表。

## 使用指南

| 操作 | 方式 |
|-----|-----|
| 平移画布 | 按住空格键 + 拖拽 |
| 缩放画布 | Ctrl + 滚轮 |
| 选择元素 | 单击 |
| 多选元素 | 框选 或 Shift + 单击 |
| 删除元素 | Delete / Backspace |
| 提交输入框 | Ctrl / ⌘ + Enter |
| 引用图片 | 在右侧输入框输入 @ |
| 选择画幅 | 在右侧输入框或图片下方重绘工具条选择 |

生成或重绘过程中刷新/关闭页面时，浏览器会弹出离开确认。若用户确认刷新，后端图片任务会继续执行；页面重新加载后会根据画布中的 `jobId` 自动恢复轮询，任务完成后回填到原来的占位图片节点。只有任务已过期或后端重启导致任务丢失时，节点才会标记为失败。

### 创作流程

1. **添加素材**：通过工具栏上传图片或添加文本
2. **创建框架**：点击“框架”按钮创建灵感捕捉区域
3. **布局组合**：在框架内摆放元素，用位置关系表达意图
4. **涂鸦标注**：切换画笔工具进行涂鸦（可选）
5. **生成输出**：输入提示词并点击生成，后端会在请求上游生图接口前自动优化提示词

### 局部重绘流程

1. 选中画布上的一张图片
2. 点击图片下方输入框上方的“局部”
3. 用画笔涂抹需要修改的区域，可用橡皮擦修正
4. 输入修改指令，例如“把涂抹区域改成蓝色花朵”
5. 选择需要的画幅比例，或保持“自动”沿用原图比例
6. 点击提交，后端会先结合图片引用和局部蒙版语义优化提示词，再调用图生图 `mask` 接口

## 技术栈

- React 19
- TypeScript
- Vite
- Tailwind CSS
- Spring Boot
- Spring Security JWT
- MyBatis Plus
- PostgreSQL
- MinIO
- RabbitMQ
- OpenAI Images API 兼容接口
- Google Gemini API（可选兼容）

## 开源来源与致谢

本项目基于 [yiqi-software/ArtisanLab](https://gitee.com/yiqi-software/ArtisanLab) 继续开发。感谢原项目提供的无限画布和 AI 图像创作基础能力。

livart 当前改动重点是把原有单机画布体验扩展为可长期使用的 AI 创作工作台：增加永久画布后端、项目管理、账号登录、JWT 鉴权、异步保存队列、图片资源存储、后端内联提示词优化、Lovart 风格图片引用、非破坏式派生重绘、画幅比例选择、局部 mask 重绘、自适应图片框、Docker 一键部署和更稳定的图像接口代理。

## 开源协议

Apache License 2.0
