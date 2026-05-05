# Livart 外部生图接口文档

本文档用于给外部系统接入 Livart 的文生图、图生图与结果查询接口。

## 调用流程

1. 调用文生图接口或图生图接口创建任务
2. 接口立即返回 `jobId`
3. 外部系统使用同一个 `API Key` 轮询查询结果接口
4. 当 `status` 变为 `completed` 或 `error` 时结束轮询

推荐轮询间隔：`2 ~ 3 秒`

---

## 鉴权方式

所有接口都使用请求头鉴权：

```http
X-Livart-Api-Key: YOUR_API_KEY
```

---

## 接口地址

生产环境域名：

```text
https://livart.suntools.pro
```

三个接口如下：

- 文生图：`POST /api/external/v1/images/generations`
- 图生图：`POST /api/external/v1/images/edits`
- 查询结果：`GET /api/external/v1/images/jobs/{jobId}`

完整地址：

- `https://livart.suntools.pro/api/external/v1/images/generations`
- `https://livart.suntools.pro/api/external/v1/images/edits`
- `https://livart.suntools.pro/api/external/v1/images/jobs/{jobId}`

---

## 1. 文生图

### 请求

```http
POST /api/external/v1/images/generations
Content-Type: application/json
X-Livart-Api-Key: YOUR_API_KEY
```

### 入参

```json
{
  "prompt": "一只小猫抓蝴蝶，真实摄影风格",
  "aspectRatio": "9:16",
  "imageResolution": "2k",
  "enablePromptOptimization": false
}
```

### 字段说明

- `prompt`：必填，提示词
- `aspectRatio`：可选，支持 `1:1`、`4:3`、`3:4`、`16:9`、`9:16`、`2:1`
- `imageResolution`：可选，支持 `1k`、`2k`、`4k`
- `enablePromptOptimization`：可选，是否开启提示词优化，`true` 或 `false`

### 成功出参

```json
{
  "success": true,
  "data": {
    "jobId": "7a4f4eb2-6df3-4dd4-9f5c-6e4d2d0b5c11",
    "status": "queued",
    "label": "text-to-image",
    "createdAt": 1777960000000,
    "updatedAt": 1777960000000,
    "attempts": 0,
    "maxConcurrentJobs": 16,
    "originalPrompt": "一只小猫抓蝴蝶，真实摄影风格"
  },
  "error": null
}
```

---

## 2. 图生图

### 请求

```http
POST /api/external/v1/images/edits
Content-Type: application/json
X-Livart-Api-Key: YOUR_API_KEY
```

### 入参

```json
{
  "prompt": "把鞋子换成红色高跟鞋",
  "imageBase64": "data:image/png;base64,iVBORw0KGgoAAA...",
  "maskBase64": "data:image/png;base64,iVBORw0KGgoAAA...",
  "referenceImages": [
    "data:image/png;base64,iVBORw0KGgoAAA..."
  ],
  "aspectRatio": "9:16",
  "imageResolution": "2k",
  "enablePromptOptimization": true
}
```

### 字段说明

- `prompt`：必填，编辑提示词
- `imageBase64`：必填，原图内容，支持：
  - 纯 Base64
  - `data:image/png;base64,...` 这种 Data URL
- `maskBase64`：可选，局部编辑蒙版
- `referenceImages`：可选，参考图数组，最多 `4` 张
- `aspectRatio`：可选，支持 `1:1`、`4:3`、`3:4`、`16:9`、`9:16`、`2:1`
- `imageResolution`：可选，支持 `1k`、`2k`、`4k`
- `enablePromptOptimization`：可选，是否开启提示词优化

### 成功出参

```json
{
  "success": true,
  "data": {
    "jobId": "8f3ec42c-874f-45d1-a7aa-7a0b0e9d7f21",
    "status": "queued",
    "label": "image-to-image",
    "createdAt": 1777960001000,
    "updatedAt": 1777960001000,
    "attempts": 0,
    "maxConcurrentJobs": 16,
    "originalPrompt": "把鞋子换成红色高跟鞋"
  },
  "error": null
}
```

### 图生图图片限制

- 单张图片建议不超过 `25MB`
- 查询结果时必须使用创建任务时的同一个 `API Key`

---

## 3. 查询生成结果

### 请求

```http
GET /api/external/v1/images/jobs/{jobId}
X-Livart-Api-Key: YOUR_API_KEY
```

### 路径参数

- `jobId`：创建任务接口返回的任务 ID

---

## 查询结果示例

### 1）排队中

```json
{
  "success": true,
  "data": {
    "jobId": "7a4f4eb2-6df3-4dd4-9f5c-6e4d2d0b5c11",
    "status": "queued",
    "label": "text-to-image",
    "queuePosition": 2,
    "queued": true,
    "createdAt": 1777960000000,
    "updatedAt": 1777960000000,
    "attempts": 0,
    "maxConcurrentJobs": 16
  },
  "error": null
}
```

### 2）执行中

```json
{
  "success": true,
  "data": {
    "jobId": "7a4f4eb2-6df3-4dd4-9f5c-6e4d2d0b5c11",
    "status": "running",
    "label": "text-to-image",
    "createdAt": 1777960000000,
    "updatedAt": 1777960003000,
    "attempts": 0,
    "maxConcurrentJobs": 16
  },
  "error": null
}
```

### 3）执行成功

```json
{
  "success": true,
  "data": {
    "jobId": "7a4f4eb2-6df3-4dd4-9f5c-6e4d2d0b5c11",
    "status": "completed",
    "label": "text-to-image",
    "createdAt": 1777960000000,
    "updatedAt": 1777960012000,
    "attempts": 1,
    "maxConcurrentJobs": 16,
    "upstreamStatus": 200,
    "contentType": "application/json; charset=utf-8",
    "response": {
      "data": [
        {
          "b64_json": "..."
        }
      ]
    }
  },
  "error": null
}
```

说明：

- `data.response` 为上游生图接口原样返回的 JSON
- 上游有可能返回：
  - `b64_json`
  - `url`
  - 其他图像相关字段

### 4）执行失败

```json
{
  "success": true,
  "data": {
    "jobId": "7a4f4eb2-6df3-4dd4-9f5c-6e4d2d0b5c11",
    "status": "error",
    "label": "text-to-image",
    "createdAt": 1777960000000,
    "updatedAt": 1777960012000,
    "attempts": 1,
    "maxConcurrentJobs": 16,
    "upstreamStatus": 502,
    "contentType": "application/json; charset=utf-8",
    "error": {
      "message": "图片生成失败：上游 AI 生成连接中断，可能是生成耗时过长或网关超时，请稍后重试；如果多次出现，可以先降低画幅/分辨率再试。",
      "upstreamStatus": 502,
      "attempts": 1,
      "code": "UPSTREAM_CONNECTION_INTERRUPTED",
      "type": "upstream_network"
    }
  },
  "error": null
}
```

---

## 轮询建议

外部系统拿到 `jobId` 后，建议按以下逻辑轮询：

- `status = queued`：继续轮询
- `status = running`：继续轮询
- `status = completed`：停止轮询，读取 `data.response`
- `status = error`：停止轮询，读取 `data.error`

推荐间隔：

```text
每 2~3 秒轮询一次
```

---

## 鉴权失败示例

```json
{
  "success": false,
  "data": null,
  "error": {
    "message": "API Key 无效",
    "code": "EXTERNAL_API_KEY_INVALID"
  }
}
```

---

## curl 示例

### 文生图

```bash
curl -X POST 'https://livart.suntools.pro/api/external/v1/images/generations' \
  -H 'Content-Type: application/json' \
  -H 'X-Livart-Api-Key: YOUR_API_KEY' \
  -d '{
    "prompt": "一只小猫抓蝴蝶，真实摄影风格",
    "aspectRatio": "9:16",
    "imageResolution": "2k",
    "enablePromptOptimization": false
  }'
```

### 图生图

```bash
curl -X POST 'https://livart.suntools.pro/api/external/v1/images/edits' \
  -H 'Content-Type: application/json' \
  -H 'X-Livart-Api-Key: YOUR_API_KEY' \
  -d '{
    "prompt": "把鞋子换成红色高跟鞋",
    "imageBase64": "data:image/png;base64,xxxx",
    "aspectRatio": "9:16",
    "imageResolution": "2k",
    "enablePromptOptimization": true
  }'
```

### 查询结果

```bash
curl 'https://livart.suntools.pro/api/external/v1/images/jobs/7a4f4eb2-6df3-4dd4-9f5c-6e4d2d0b5c11' \
  -H 'X-Livart-Api-Key: YOUR_API_KEY'
```

