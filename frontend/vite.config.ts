import path from 'path';
import type { IncomingMessage, ServerResponse } from 'http';
import { defineConfig, loadEnv } from 'vite';
import react from '@vitejs/plugin-react';

const REQUEST_TIMEOUT_MS = 10 * 60 * 1000;
const PROMPT_OPTIMIZER_TIMEOUT_MS = 2 * 60 * 1000;
const IMAGE_PROXY_MAX_ATTEMPTS = 3;

const joinUrl = (baseUrl: string, route: string) => {
  if (!baseUrl) return '';
  return `${baseUrl.replace(/\/+$/, '')}/${route.replace(/^\/+/, '')}`;
};

const readRequestBody = async (request: IncomingMessage) => {
  const chunks: Buffer[] = [];
  for await (const chunk of request) {
    chunks.push(Buffer.isBuffer(chunk) ? chunk : Buffer.from(chunk));
  }
  return Buffer.concat(chunks);
};

const writeJson = (response: ServerResponse, statusCode: number, payload: unknown) => {
  response.statusCode = statusCode;
  response.setHeader('Content-Type', 'application/json; charset=utf-8');
  response.end(JSON.stringify(payload));
};

const wait = (delayMs: number) => new Promise(resolve => setTimeout(resolve, delayMs));

const getBodyPreview = (body: Buffer) => {
  return body.toString('utf8').replace(/\s+/g, ' ').slice(0, 500);
};

const shouldRetryUpstreamResponse = (statusCode: number, body: Buffer) => {
  if (statusCode < 500 || statusCode > 599) return false;
  const preview = getBodyPreview(body).toLowerCase();
  return (
    preview.includes('stream disconnected') ||
    preview.includes('internal_server_error') ||
    preview.includes('server_error') ||
    statusCode === 502 ||
    statusCode === 503 ||
    statusCode === 504
  );
};

const getJsonBody = async (request: IncomingMessage) => {
  const body = await readRequestBody(request);
  if (!body.length) return {};
  return JSON.parse(body.toString('utf8'));
};

const getBearerToken = (authorization: string | string[] | undefined) => {
  const value = Array.isArray(authorization) ? authorization[0] : authorization;
  if (!value) return '';
  const match = value.match(/^Bearer\s+(.+)$/i);
  return match?.[1]?.trim() || '';
};

const getHeaderValue = (value: string | string[] | undefined) => Array.isArray(value) ? value[0] : value || '';
const getLivartApiKey = (request: IncomingMessage) => getHeaderValue(request.headers['x-livart-api-key']).trim();

const resolveImageTargetUrl = (request: IncomingMessage, fallbackTargetUrl: string) => {
  const configuredTargetUrl = getHeaderValue(request.headers['x-livart-upstream-url']).trim();
  const targetUrl = configuredTargetUrl || fallbackTargetUrl;
  if (!targetUrl) return '';

  try {
    const parsedUrl = new URL(targetUrl);
    return ['http:', 'https:'].includes(parsedUrl.protocol) ? parsedUrl.toString() : '';
  } catch {
    return '';
  }
};

const sanitizeOptimizedPrompt = (text: string) => {
  return text
    .replace(/^```(?:text|markdown)?/i, '')
    .replace(/```$/i, '')
    .replace(/^优化后提示词[:：]\s*/i, '')
    .trim();
};

const NEGATIVE_PROMPT_TERMS = [
  '画布 UI',
  '工具栏',
  '选中框',
  '控制点',
  '鼠标指针',
  '网格线',
  '截图界面',
  '边框',
  '水印',
  'logo',
  '签名',
  '二维码',
  '条形码',
  '无关文字',
  '字幕',
  '乱码文字',
  '低质量',
  '低清晰度',
  '模糊',
  '失焦',
  '噪点',
  '马赛克',
  '压缩痕迹',
  'JPEG artifacts',
  '过曝',
  '欠曝',
  '色彩脏污',
  '过度锐化',
  '过度平滑',
  '塑料感',
  'AI 痕迹',
  '拼接痕迹',
  '构图混乱',
  '主体被裁切',
  '比例失衡',
  '透视错误',
  '结构扭曲',
  '重复主体',
  '畸形肢体',
  '多余肢体',
  '缺失肢体',
  '多指',
  '少指',
  '手指错误',
  '融合手指',
  '扭曲手部',
  '脸部崩坏',
  '五官错位',
  '眼睛不对称',
  '牙齿异常',
  '表情僵硬'
];

const NEGATIVE_PROMPT_TEXT = `负面约束：避免${NEGATIVE_PROMPT_TERMS.join('、')}。`;

const appendNegativePromptConstraints = (prompt: string) => {
  const trimmedPrompt = prompt.trim();
  if (!trimmedPrompt || trimmedPrompt.includes(NEGATIVE_PROMPT_TEXT)) {
    return trimmedPrompt;
  }

  const normalizedPrompt = trimmedPrompt.replace(/[。；;，,\s]+$/u, '');
  return `${normalizedPrompt}。${NEGATIVE_PROMPT_TEXT}`;
};

const extractTextFromAiResponse = (data: any): string => {
  if (typeof data.output_text === 'string') {
    return data.output_text;
  }

  if (Array.isArray(data.output)) {
    for (const outputItem of data.output) {
      if (Array.isArray(outputItem.content)) {
        for (const contentItem of outputItem.content) {
          if (typeof contentItem.text === 'string') return contentItem.text;
        }
      }
    }
  }

  const chatText = data.choices?.[0]?.message?.content;
  if (typeof chatText === 'string') {
    return chatText;
  }

  throw new Error('未能从提示词优化响应中获取文本');
};

const getPromptOptimizerSystemPrompt = (mode: string) => {
  const sharedRules = `你是专业 AI 图像提示词优化器。只输出优化后的提示词，不要解释，不要 Markdown，不要加标题。
要求：
- 保留用户原始意图，不新增用户没有要求的主体或文字。
- 补充清晰的主体、场景、构图、风格、材质、色彩、光影、镜头/视角和质量描述。
- 必须在提示词末尾加入完整负面约束：${NEGATIVE_PROMPT_TEXT}
- 使用中文输出，保持一段完整提示词。`;

  if (mode === 'image-to-image') {
    return `${sharedRules}
- 当前任务是图生图/重绘，必须强调保留参考图主体身份、结构、比例、构图和关键特征。
- 只强化用户要求修改的部分，不要把参考图重写成完全不同画面。`;
  }

  return `${sharedRules}
- 当前任务是文生图，需要把短描述扩写成可直接用于高质量图像生成的完整视觉 brief。`;
};

const callPromptOptimizerEndpoint = async (
  targetUrl: string,
  apiKey: string,
  body: unknown
) => {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), PROMPT_OPTIMIZER_TIMEOUT_MS);

  try {
    const response = await fetch(targetUrl, {
      method: 'POST',
      headers: {
        'Content-Type': 'application/json',
        'Accept': 'application/json',
        'Authorization': `Bearer ${apiKey}`
      },
      body: JSON.stringify(body),
      signal: controller.signal
    });

    const responseBody = Buffer.from(await response.arrayBuffer());
    return {
      ok: response.ok,
      status: response.status,
      contentType: response.headers.get('content-type') || 'application/json; charset=utf-8',
      body: responseBody
    };
  } finally {
    clearTimeout(timeoutId);
  }
};

const proxyPromptOptimizeRequest = async (
  request: IncomingMessage,
  response: ServerResponse,
  baseUrl: string,
  apiKey: string,
  model: string
) => {
  if (request.method === 'OPTIONS') {
    response.statusCode = 204;
    response.end();
    return;
  }

  if (request.method !== 'POST') {
    writeJson(response, 405, { error: 'prompt optimizer only supports POST' });
    return;
  }

  try {
    const body = await getJsonBody(request);
    const prompt = String(body.prompt || '').trim();
    const mode = body.mode === 'image-to-image' ? 'image-to-image' : 'text-to-image';
    const effectiveBaseUrl = String(body.baseUrl || baseUrl || '').trim();
    const effectiveApiKey = getLivartApiKey(request) || getBearerToken(request.headers.authorization) || apiKey;
    const effectiveModel = String(body.model || model || '').trim();

    if (!prompt) {
      writeJson(response, 400, { error: '请输入需要优化的提示词' });
      return;
    }

    if (!effectiveBaseUrl || !effectiveApiKey || !effectiveModel) {
      writeJson(response, 500, { error: '提示词优化器配置缺失' });
      return;
    }

    const systemPrompt = getPromptOptimizerSystemPrompt(mode);
    const userPrompt = `原始提示词：${prompt}`;
    const startedAt = Date.now();

    console.info(`[prompt-optimizer] start mode=${mode} model=${effectiveModel}`);

    const finalResult = await callPromptOptimizerEndpoint(joinUrl(effectiveBaseUrl, 'responses'), effectiveApiKey, {
      model: effectiveModel,
      instructions: systemPrompt,
      input: userPrompt
    });

    if (!finalResult.ok) {
      writeJson(response, finalResult.status, {
        error: '提示词优化 responses 接口请求失败',
        detail: getBodyPreview(finalResult.body)
      });
      return;
    }

    const data = JSON.parse(finalResult.body.toString('utf8'));
    const optimizedPrompt = appendNegativePromptConstraints(sanitizeOptimizedPrompt(extractTextFromAiResponse(data)));

    writeJson(response, 200, {
      optimizedPrompt,
      model: effectiveModel,
      mode
    });
    console.info(`[prompt-optimizer] done mode=${mode} duration=${Date.now() - startedAt}ms`);
  } catch (error) {
    const isAbort = error instanceof DOMException && error.name === 'AbortError';
    const message = error instanceof Error ? error.message : String(error);
    console.error(`[prompt-optimizer] failed ${message}`);
    writeJson(response, isAbort ? 504 : 500, {
      error: isAbort ? '提示词优化超过 2 分钟，请稍后重试' : '提示词优化失败',
      detail: message
    });
  }
};

const proxyImageRequest = async (
  label: string,
  targetUrl: string,
  request: IncomingMessage,
  response: ServerResponse,
  fallbackApiKey: string
) => {
  if (request.method === 'OPTIONS') {
    response.statusCode = 204;
    response.end();
    return;
  }

  if (request.method !== 'POST') {
    writeJson(response, 405, { error: `${label} only supports POST` });
    return;
  }

  const resolvedTargetUrl = resolveImageTargetUrl(request, targetUrl);

  if (!resolvedTargetUrl) {
    writeJson(response, 500, { error: `${label} upstream URL is missing` });
    return;
  }

  const startedAt = Date.now();
  const body = await readRequestBody(request);
  const deadlineAt = startedAt + REQUEST_TIMEOUT_MS;

  const headers: Record<string, string> = {
    Accept: String(request.headers.accept || 'application/json'),
    Authorization: `Bearer ${getLivartApiKey(request) || getBearerToken(request.headers.authorization) || fallbackApiKey}`
  };

  if (request.headers['content-type']) {
    headers['Content-Type'] = String(request.headers['content-type']);
  }

  console.info(`[image-proxy] ${label} start -> ${resolvedTargetUrl}`);

  for (let attempt = 1; attempt <= IMAGE_PROXY_MAX_ATTEMPTS; attempt += 1) {
    const remainingTimeoutMs = Math.max(0, deadlineAt - Date.now());
    if (remainingTimeoutMs <= 0) {
      console.error(`[image-proxy] ${label} timeout before attempt=${attempt} duration=${Date.now() - startedAt}ms`);
      writeJson(response, 504, {
        error: `${label} upstream request timed out after 10 minutes`,
        attempts: attempt - 1
      });
      return;
    }

    const controller = new AbortController();
    const timeoutId = setTimeout(() => controller.abort(), remainingTimeoutMs);

    try {
      console.info(`[image-proxy] ${label} attempt=${attempt}/${IMAGE_PROXY_MAX_ATTEMPTS}`);
      const upstreamResponse = await fetch(resolvedTargetUrl, {
        method: 'POST',
        headers,
        body,
        signal: controller.signal
      });
      const responseBody = Buffer.from(await upstreamResponse.arrayBuffer());
      const requestId = upstreamResponse.headers.get('x-oneapi-request-id') || upstreamResponse.headers.get('x-request-id') || '';
      const contentType = upstreamResponse.headers.get('content-type') || 'application/json; charset=utf-8';
      const shouldRetry = attempt < IMAGE_PROXY_MAX_ATTEMPTS && shouldRetryUpstreamResponse(upstreamResponse.status, responseBody);

      if (shouldRetry) {
        console.warn(`[image-proxy] ${label} retry status=${upstreamResponse.status} attempt=${attempt} duration=${Date.now() - startedAt}ms requestId=${requestId || '-'} body=${getBodyPreview(responseBody)}`);
        await wait(1000 * attempt);
        continue;
      }

      response.statusCode = upstreamResponse.status;
      response.setHeader('Content-Type', contentType);
      response.setHeader('Cache-Control', 'no-store');
      response.setHeader('X-Proxy-Attempts', String(attempt));
      if (requestId) response.setHeader('X-Upstream-Request-Id', requestId);
      response.end(responseBody);

      console.info(`[image-proxy] ${label} done status=${upstreamResponse.status} attempts=${attempt} duration=${Date.now() - startedAt}ms requestId=${requestId || '-'}`);
      return;
    } catch (error) {
      const message = error instanceof Error ? error.message : String(error);
      const isAbort = error instanceof DOMException && error.name === 'AbortError';
      const canRetry = !isAbort && attempt < IMAGE_PROXY_MAX_ATTEMPTS;

      if (canRetry) {
        console.warn(`[image-proxy] ${label} retry error attempt=${attempt} duration=${Date.now() - startedAt}ms error=${message}`);
        await wait(1000 * attempt);
        continue;
      }

      console.error(`[image-proxy] ${label} failed attempts=${attempt} duration=${Date.now() - startedAt}ms error=${message}`);
      writeJson(response, isAbort ? 504 : 502, {
        error: isAbort ? `${label} upstream request timed out after 10 minutes` : `${label} upstream request failed`,
        detail: message,
        attempts: attempt
      });
      return;
    } finally {
      clearTimeout(timeoutId);
    }
  }
};

export default defineConfig(({ mode }) => {
    const env = loadEnv(mode, '.', '');
    const textToImageUpstreamUrl = env.IMAGE_UPSTREAM_TEXT_TO_IMAGE_URL || env.TEXT_TO_IMAGE_UPSTREAM_URL || joinUrl(env.IMAGE_API_BASE_URL, 'images/generations');
    const imageToImageUpstreamUrl = env.IMAGE_UPSTREAM_IMAGE_TO_IMAGE_URL || env.IMAGE_TO_IMAGE_UPSTREAM_URL || joinUrl(env.IMAGE_API_BASE_URL, 'images/edits');
    const promptOptimizerBaseUrl = env.PROMPT_OPTIMIZER_BASE_URL || env.IMAGE_API_BASE_URL || '';
    const promptOptimizerApiKey = env.PROMPT_OPTIMIZER_API_KEY || env.IMAGE_API_KEY;
    const promptOptimizerModel = env.PROMPT_OPTIMIZER_MODEL || 'gpt-5.5';
    const backendApiBaseUrl = env.BACKEND_API_BASE_URL || 'http://localhost:8080';

    return {
      server: {
        port: 3000,
        host: '0.0.0.0',
        proxy: {
          '/api/canvases': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/canvas': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/assets': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/auth': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/user': {
            target: backendApiBaseUrl,
            changeOrigin: true
          },
          '/api/health': {
            target: backendApiBaseUrl,
            changeOrigin: true
          }
        }
      },
      plugins: [
        react(),
        {
          name: 'livart-image-proxy',
          configureServer(server) {
            server.middlewares.use('/api/images/generations', (request, response) => {
              proxyImageRequest('text-to-image', textToImageUpstreamUrl, request, response, env.IMAGE_API_KEY);
            });
            server.middlewares.use('/api/images/edits', (request, response) => {
              proxyImageRequest('image-to-image', imageToImageUpstreamUrl, request, response, env.IMAGE_API_KEY);
            });
            server.middlewares.use('/api/prompts/optimize', (request, response) => {
              proxyPromptOptimizeRequest(request, response, promptOptimizerBaseUrl, promptOptimizerApiKey, promptOptimizerModel);
            });
          }
        }
      ],
      define: {
        'process.env.IMAGE_API_BASE_URL': JSON.stringify(env.IMAGE_API_BASE_URL),
        'process.env.IMAGE_API_MODEL': JSON.stringify(env.IMAGE_API_MODEL),
        'process.env.PROMPT_OPTIMIZER_MODEL': JSON.stringify(env.PROMPT_OPTIMIZER_MODEL),
        'process.env.CHAT_API_MODEL': JSON.stringify(env.CHAT_API_MODEL),
        'process.env.TEXT_TO_IMAGE_API_URL': JSON.stringify(env.TEXT_TO_IMAGE_API_URL || '/api/images/generations'),
        'process.env.IMAGE_TO_IMAGE_API_URL': JSON.stringify(env.IMAGE_TO_IMAGE_API_URL || '/api/images/edits'),
        'process.env.IMAGE_TEXT_TO_IMAGE_URL': JSON.stringify(env.IMAGE_TEXT_TO_IMAGE_URL),
        'process.env.IMAGE_IMAGE_TO_IMAGE_URL': JSON.stringify(env.IMAGE_IMAGE_TO_IMAGE_URL)
      },
      resolve: {
        alias: {
          '@': path.resolve(__dirname, '.'),
        }
      }
    };
});
