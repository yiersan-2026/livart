<div align="center">

# livart

**无限画布 AI 图像创作工作台**

![livart 真实界面截图](./docs/screenshots/livart-canvas.png)

</div>

---

## 项目简介

livart 是一款基于 **无限画布** 的 AI 图像创作工具。它把文生图、图生图、局部重绘、图片引用、拖拽素材、项目画布和历史记录整合到一个自由画布中，适合用视觉布局表达创作意图。

本项目基于上一个开源系统 [yiqi-software/ArtisanLab](https://gitee.com/yiqi-software/ArtisanLab) 二次开发而来。原项目提供了无限画布、基础 AI 图像生成、画布元素编辑和视觉逻辑工作流能力；livart 在此基础上补充了更完整的图生图、局部重绘、永久画布、账号体系、后端持久化和 Lovart 风格交互。

当前开源地址：[sunowen/livart](https://gitee.com/sunowen/livart.git)

## 本版本新增能力

- **独立生图接口配置**：文生图和图生图使用不同接口，默认支持 OpenAI Images API 兼容格式。
- **图生图与局部重绘**：选中图片后可直接输入指令重绘，也可进入“局部”模式涂抹区域并通过 `mask` 精确修改局部。
- **提示词自动优化**：提交前自动调用提示词优化模型，补全画面描述、质量要求和安全约束。
- **Lovart 风格图片引用**：右侧输入框支持输入 `@` 选择画布图片，并以可删除的内联标签参与提示词上下文。
- **图片拖入画布**：支持直接把本机图片拖进画布，自动创建图片节点。
- **选中图片下方对话框**：选中单张图片后显示 3 行输入框，可用 `Ctrl/⌘ + Enter` 快速提交。
- **并行图片处理**：多个图片的重绘状态互不阻塞，切换图片不会清空各自输入内容。
- **永久画布后端**：新增 Spring Boot + MyBatis Plus 后端，用 PostgreSQL 保存画布项目，用 MinIO 保存图片资源。
- **Spring Security JWT 登录**：支持账号注册、登录和 30 天 JWT，会话清理后重新登录即可找回账号下的项目画布。
- **异步保存队列**：画布保存请求进入 RabbitMQ，后端单消费者按顺序落库，并通过 revision 避免旧数据覆盖新数据。
- **多项目画布**：支持创建多个项目，一个项目对应一张独立画布，并记住最近打开的项目。
- **画布体验优化**：缩放逻辑改为以鼠标位置为锚点，输入框滚动和键盘快捷键冲突已修复。

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

- Node.js 18+
- Java 17+
- PostgreSQL
- MinIO
- RabbitMQ
- 支持 OpenAI Images API 兼容接口或 Gemini 图像接口的网络环境

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

前端开发服务器会把 `/api/auth`、`/api/canvases`、`/api/canvas`、`/api/assets` 和 `/api/health` 代理到 `http://localhost:8080`。

首次进入页面需要注册或登录账号。登录成功后，前端会把 JWT 保存在浏览器本地；如果浏览器缓存被清理，只要重新登录同一个账号，就会从后端加载该账号下的项目画布历史。

### API 配置

点击界面左上角的设置图标，填入：

- API 地址（支持自定义代理，默认前端代理为 `/api/images/generations` 和 `/api/images/edits`）
- API 密钥
- 生图模型（默认 `gpt-image-2`，也兼容部分 Gemini 图像模型）
- 对话模型（默认 `gpt-5.5`，用于提示词自动优化）

也可以在 `frontend/.env.local` 中配置：

```bash
IMAGE_API_BASE_URL=https://example.com/v1/
IMAGE_API_MODEL=gpt-image-2
IMAGE_API_KEY=your-api-key
TEXT_TO_IMAGE_API_URL=/api/images/generations
IMAGE_TO_IMAGE_API_URL=/api/images/edits
PROMPT_OPTIMIZER_MODEL=gpt-5.5
```

不要把真实 `frontend/.env.local`、`backend/.env` 或任何 API Key 提交到仓库。

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

### 创作流程

1. **添加素材**：通过工具栏上传图片或添加文本
2. **创建框架**：点击“框架”按钮创建灵感捕捉区域
3. **布局组合**：在框架内摆放元素，用位置关系表达意图
4. **涂鸦标注**：切换画笔工具进行涂鸦（可选）
5. **生成输出**：输入提示词并点击生成，系统会自动优化提示词

### 局部重绘流程

1. 选中画布上的一张图片
2. 点击图片下方输入框上方的“局部”
3. 用画笔涂抹需要修改的区域，可用橡皮擦修正
4. 输入修改指令，例如“把涂抹区域改成蓝色花朵”
5. 点击提交，系统会自动优化提示词并调用图生图 `mask` 接口

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

livart 当前改动重点是把原有单机画布体验扩展为可长期使用的 AI 创作工作台：增加永久画布后端、项目管理、账号登录、JWT 鉴权、异步保存队列、图片资源存储、提示词优化、Lovart 风格图片引用、选中图片快捷重绘、局部 mask 重绘和更稳定的图像接口代理。

## 开源协议

MIT License
