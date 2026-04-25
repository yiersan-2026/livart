import { getApiConfig, hasApiConfig } from './config';
import { authHeaders, getStoredAuthSession } from './auth';
import type { ImageAspectRatio } from '../types';
import { aspectRatioToGeminiAspectRatio, aspectRatioToImageApiSize } from './imageSizing';

const REQUEST_TIMEOUT_MS = 10 * 60 * 1000;
const JOB_POLL_INTERVAL_MS = 2 * 1000;
const TEXT_TO_IMAGE_PROXY_URL = '/api/images/generations';
const IMAGE_TO_IMAGE_PROXY_URL = '/api/images/edits';
const TEXT_TO_IMAGE_JOB_URL = '/api/image-jobs/generations';
const IMAGE_TO_IMAGE_JOB_URL = '/api/image-jobs/edits';
const IMAGE_JOB_STATUS_URL = '/api/image-jobs';
const IMAGE_JOB_WS_PATH = '/ws/image-jobs';

const isGeminiModel = (model: string) => model.startsWith('gemini-');

export interface ImageJobSubmission {
  jobId: string;
  status: 'queued' | 'running' | 'completed' | 'error';
}

interface ImageJobStatus extends ImageJobSubmission {
  response?: unknown;
  error?: unknown;
  upstreamStatus?: number;
  requestId?: string;
  attempts?: number;
}

interface ImageJobSocketMessage {
  type?: string;
  job?: ImageJobStatus;
  jobId?: string;
  error?: unknown;
}

class ImageJobWebSocketUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ImageJobWebSocketUnavailableError';
  }
}

const extractBase64 = (data: string) => data.includes(',') ? data.split(',')[1] : data;

const dataUrlToBlob = async (dataUrl: string) => {
  const response = await fetch(dataUrl);
  return response.blob();
};

const fetchWithTimeout = async (url: string, options: RequestInit, timeoutMessage: string) => {
  const controller = new AbortController();
  const timeoutId = setTimeout(() => controller.abort(), REQUEST_TIMEOUT_MS);

  try {
    return await fetch(url, {
      ...options,
      signal: controller.signal
    });
  } catch (error) {
    if (error instanceof DOMException && error.name === 'AbortError') {
      throw new Error(timeoutMessage);
    }
    throw error;
  } finally {
    clearTimeout(timeoutId);
  }
};

const imageProxyHeaders = (config: ReturnType<typeof getApiConfig>) => ({
  'Accept': 'application/json',
  ...authHeaders(),
  'X-Livart-Api-Key': config.apiKey,
  'X-Livart-Upstream-Url': config.textToImageUrl
});

const imageEditProxyHeaders = (config: ReturnType<typeof getApiConfig>) => ({
  'Accept': 'application/json',
  ...authHeaders(),
  'X-Livart-Api-Key': config.apiKey,
  'X-Livart-Upstream-Url': config.imageToImageUrl
});

const wait = (delayMs: number) => new Promise(resolve => setTimeout(resolve, delayMs));

const getImageJobWebSocketUrl = () => {
  if (typeof window === 'undefined') return '';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${IMAGE_JOB_WS_PATH}`;
};

const callGeminiApi = async (contents: any, imageConfig?: any) => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const url = `${config.baseUrl}/v1beta/models/${config.model}:generateContent`;

  const body: any = {
    contents: [{ role: 'user', parts: contents }],
    generationConfig: {
      responseModalities: ['TEXT', 'IMAGE']
    }
  };

  if (imageConfig) {
    body.generationConfig.imageConfig = imageConfig;
  }

  const response = await fetchWithTimeout(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Authorization': `Bearer ${config.apiKey}`
    },
    body: JSON.stringify(body)
  }, 'Gemini 图像请求超过 10 分钟仍未完成，请稍后重试');

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`API 请求失败: ${error}`);
  }

  return response.json();
};

const callImageGenerationApi = async (prompt: string, aspectRatio: ImageAspectRatio = 'auto') => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const size = aspectRatioToImageApiSize(aspectRatio);
  const response = await fetchWithTimeout(TEXT_TO_IMAGE_PROXY_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...imageProxyHeaders(config)
    },
    body: JSON.stringify({
      model: config.model,
      prompt,
      ...(size ? { size } : {})
    })
  }, '文生图请求超过 10 分钟仍未完成，请稍后重试');

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`API 请求失败: ${error}`);
  }

  return response.json();
};

const callImageEditApi = async (
  prompt: string,
  imageDataUrl: string,
  maskDataUrl?: string,
  aspectRatio: ImageAspectRatio = 'auto'
) => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const size = aspectRatioToImageApiSize(aspectRatio);
  const formData = new FormData();
  formData.append('model', config.model);
  formData.append('prompt', prompt);
  if (size) {
    formData.append('size', size);
  }
  formData.append('image', await dataUrlToBlob(imageDataUrl), 'canvas.png');
  if (maskDataUrl) {
    formData.append('mask', await dataUrlToBlob(maskDataUrl), 'mask.png');
  }

  const response = await fetchWithTimeout(IMAGE_TO_IMAGE_PROXY_URL, {
    method: 'POST',
    headers: imageEditProxyHeaders(config),
    body: formData
  }, '图生图请求超过 10 分钟仍未完成，请稍后重试');

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`API 请求失败: ${error}`);
  }

  return response.json();
};

const extractImageFromResponse = (data: any): string => {
  const imageData = data.data?.[0] || data.images?.[0];
  if (imageData?.b64_json) {
    return `data:image/png;base64,${imageData.b64_json}`;
  }
  if (imageData?.url) {
    return imageData.url;
  }

  if (data.candidates?.[0]?.content?.parts) {
    for (const part of data.candidates[0].content.parts) {
      if (part.inlineData) {
        return `data:image/png;base64,${part.inlineData.data}`;
      }
    }
  }
  throw new Error('未能从响应中获取图像数据');
};

const getErrorText = (payload: unknown): string => {
  if (!payload) return '图片任务失败';
  if (typeof payload === 'string') return payload;
  if (typeof payload === 'object') {
    const data = payload as Record<string, unknown>;
    if (data.message) return String(data.message);
    if (data.error && typeof data.error === 'object') {
      const nestedError = data.error as Record<string, unknown>;
      return String(nestedError.message || nestedError.detail || nestedError.code || '图片任务失败');
    }
    if (typeof data.error === 'string') return data.error;
    return String(data.detail || data.code || '图片任务失败');
  }
  return '图片任务失败';
};

const extractApiErrorMessage = (payload: unknown) => {
  if (!payload) return '';
  if (typeof payload === 'string') return payload;
  if (typeof payload === 'object') {
    const data = payload as Record<string, unknown>;
    if (data.error) return getErrorText(data.error);
    if (data.message) return String(data.message);
    if (data.detail) return String(data.detail);
  }
  return '';
};

const getStringField = (payload: unknown, fieldName: string) => {
  if (!payload || typeof payload !== 'object') return '';
  const value = (payload as Record<string, unknown>)[fieldName];
  return typeof value === 'string' ? value : '';
};

const extractImageJobError = (payload: unknown, job?: ImageJobStatus) => {
  const message = getErrorText(payload);
  const upstreamStatus = job?.upstreamStatus || (payload && typeof payload === 'object'
    ? Number((payload as Record<string, unknown>).upstreamStatus || 0)
    : 0);
  const requestId = job?.requestId || getStringField(payload, 'requestId');
  const code = getStringField(payload, 'code');
  const type = getStringField(payload, 'type');

  if (upstreamStatus || requestId || code || type) {
    const details = [
      upstreamStatus ? `状态 ${upstreamStatus}` : '',
      code ? `code=${code}` : '',
      type ? `type=${type}` : '',
      requestId ? `requestId=${requestId}` : ''
    ].filter(Boolean).join('，');
    return `上游 AI 接口错误：${message}${details ? `（${details}）` : ''}`;
  }

  return message;
};

export const canUseImageJobs = () => {
  const config = getApiConfig();
  return !isGeminiModel(config.model);
};

export const submitImageGenerationJob = async (
  prompt: string,
  aspectRatio: ImageAspectRatio = 'auto'
): Promise<ImageJobSubmission> => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const size = aspectRatioToImageApiSize(aspectRatio);
  const response = await fetch(TEXT_TO_IMAGE_JOB_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({
      model: config.model,
      prompt,
      ...(size ? { size } : {})
    })
  });

  const data = await response.json().catch(() => null);
  if (!response.ok || !data?.jobId) {
    throw new Error(data?.error || `图片任务提交失败：${response.status}`);
  }
  return data;
};

export const submitImageEditJob = async (
  prompt: string,
  imageDataUrl: string,
  maskDataUrl?: string,
  aspectRatio: ImageAspectRatio = 'auto'
): Promise<ImageJobSubmission> => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const size = aspectRatioToImageApiSize(aspectRatio);
  const formData = new FormData();
  formData.append('model', config.model);
  formData.append('prompt', prompt);
  if (size) {
    formData.append('size', size);
  }
  formData.append('image', await dataUrlToBlob(imageDataUrl), 'canvas.png');
  if (maskDataUrl) {
    formData.append('mask', await dataUrlToBlob(maskDataUrl), 'mask.png');
  }

  const response = await fetch(IMAGE_TO_IMAGE_JOB_URL, {
    method: 'POST',
    headers: {
      'Accept': 'application/json',
      ...authHeaders()
    },
    body: formData
  });

  const data = await response.json().catch(() => null);
  if (!response.ok || !data?.jobId) {
    throw new Error(data?.error || `图片任务提交失败：${response.status}`);
  }
  return data;
};

export const getImageJob = async (jobId: string): Promise<ImageJobStatus> => {
  const response = await fetch(`${IMAGE_JOB_STATUS_URL}/${encodeURIComponent(jobId)}`, {
    headers: {
      'Accept': 'application/json',
      ...authHeaders()
    }
  });

  const responseText = await response.text().catch(() => '');
  const data = responseText
    ? (() => {
      try {
        return JSON.parse(responseText);
      } catch {
        return responseText;
      }
    })()
    : null;
  if (!response.ok) {
    if (response.status === 404) {
      throw new Error('图片任务不存在或已过期。若刚刚重启过后端，生成任务状态会丢失，请重新生成。');
    }
    if (response.status >= 500) {
      const apiMessage = extractApiErrorMessage(data);
      const detail = apiMessage ? `后端返回：${apiMessage}` : '后端暂时不可用或正在重启';
      throw new Error(`图片任务查询失败：${detail}。如果任务是在后端重启前创建的，请重新生成。`);
    }
    throw new Error(extractApiErrorMessage(data) || `图片任务查询失败：${response.status}`);
  }
  return data;
};

const waitForImageJobByPolling = async (jobId: string) => {
  const deadlineAt = Date.now() + REQUEST_TIMEOUT_MS;

  while (Date.now() < deadlineAt) {
    const job = await getImageJob(jobId);
    if (job.status === 'completed') {
      return extractImageFromResponse(job.response);
    }
    if (job.status === 'error') {
      throw new Error(extractImageJobError(job.error, job));
    }
    await wait(JOB_POLL_INTERVAL_MS);
  }

  throw new Error('图片任务超过 10 分钟仍未完成，请稍后刷新页面查看');
};

const waitForImageJobByWebSocket = async (jobId: string) => {
  const session = getStoredAuthSession();
  const socketUrl = getImageJobWebSocketUrl();
  if (!session?.token || !socketUrl || typeof WebSocket === 'undefined') {
    throw new ImageJobWebSocketUnavailableError('图片任务 WebSocket 不可用');
  }

  return new Promise<string>((resolve, reject) => {
    let settled = false;
    const socket = new WebSocket(socketUrl);
    const timeoutId = window.setTimeout(() => {
      finishReject(new Error('图片任务超过 10 分钟仍未完成，请稍后刷新页面查看'));
    }, REQUEST_TIMEOUT_MS);
    const pingIntervalId = window.setInterval(() => {
      if (socket.readyState === WebSocket.OPEN) {
        socket.send(JSON.stringify({ type: 'ping' }));
      }
    }, 25 * 1000);

    const cleanup = () => {
      window.clearTimeout(timeoutId);
      window.clearInterval(pingIntervalId);
      socket.onopen = null;
      socket.onmessage = null;
      socket.onerror = null;
      socket.onclose = null;
      if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
        socket.close();
      }
    };

    const finishResolve = (value: string) => {
      if (settled) return;
      settled = true;
      cleanup();
      resolve(value);
    };

    function finishReject(error: Error) {
      if (settled) return;
      settled = true;
      cleanup();
      reject(error);
    }

    socket.onopen = () => {
      socket.send(JSON.stringify({
        type: 'auth',
        token: session.token,
        jobId
      }));
    };

    socket.onmessage = (event) => {
      let message: ImageJobSocketMessage;
      try {
        message = JSON.parse(String(event.data));
      } catch {
        finishReject(new ImageJobWebSocketUnavailableError('图片任务 WebSocket 消息格式无效'));
        return;
      }

      if (message.type === 'connected' || message.type === 'authenticated' || message.type === 'pong') {
        return;
      }

      if (message.type === 'image-job-error' && message.jobId === jobId) {
        finishReject(new Error(extractApiErrorMessage(message.error) || '图片任务不存在或已过期'));
        return;
      }

      if (message.type === 'error') {
        finishReject(new Error(extractApiErrorMessage(message.error) || '图片任务 WebSocket 连接失败'));
        return;
      }

      if (message.type !== 'image-job' || message.job?.jobId !== jobId) {
        return;
      }

      if (message.job.status === 'completed') {
        try {
          finishResolve(extractImageFromResponse(message.job.response));
        } catch (error) {
          finishReject(error instanceof Error ? error : new Error('未能从响应中获取图像数据'));
        }
        return;
      }

      if (message.job.status === 'error') {
        finishReject(new Error(extractImageJobError(message.job.error, message.job)));
      }
    };

    socket.onerror = () => {
      finishReject(new ImageJobWebSocketUnavailableError('图片任务 WebSocket 连接失败'));
    };

    socket.onclose = () => {
      finishReject(new ImageJobWebSocketUnavailableError('图片任务 WebSocket 连接已断开'));
    };
  });
};

export const waitForImageJob = async (jobId: string) => {
  try {
    return await waitForImageJobByWebSocket(jobId);
  } catch (error) {
    if (error instanceof ImageJobWebSocketUnavailableError) {
      return waitForImageJobByPolling(jobId);
    }
    throw error;
  }
};

/**
 * 视觉逻辑推理合成：支持无提示词的智能意图推断
 */
const getGeminiImageConfig = (aspectRatio: ImageAspectRatio) => {
  const geminiAspectRatio = aspectRatioToGeminiAspectRatio(aspectRatio);
  if (!geminiAspectRatio) return undefined;
  return { aspectRatio: geminiAspectRatio, imageSize: '1K' };
};

const buildWorkflowInstruction = (prompt: string) => {
  const finalPrompt = prompt.trim() || "请自动分析快照中的视觉逻辑：如果有箭头指向，执行转换；如果有并列的人和衣服，执行换装（Virtual Try-on）；如果有涂鸦，将其转化为特效。请保持人物面貌特征完全一致，输出高清写实结果。";

  return `你是一位具备顶级视觉直觉和推理能力的 AI 图像专家。

任务指令：解析提供的画布快照，并根据其中的"视觉布局"和"用户意图"生成最终的高清图像。

视觉布局推理指南：
1. 箭头 (Arrow) = 动作算子。从 A 指向 B 代表将 A 的属性应用到 B 或从 A 转换到 B。
2. 换装逻辑：如果快照左侧是人物，右侧是单件衣服（或有箭头指向），则自动执行换装。必须严格保持人物的面部、五官、体型和发型，同时完美适配右侧衣服的纹理和剪裁。
3. 涂鸦逻辑：手绘线条代表特效（如发光、能量场、路径）或需要替换的区域。

具体指令："${finalPrompt}"

质量标准：
- 输出必须是一张干净、写实、高画质的单张图像。
- 严禁出现任何画布 UI 元素（如选中框、工具栏）。
- 保持主体特征高度一致。`;
};

export const submitWorkflowImageJob = async (
  prompt: string,
  snapshot: string,
  aspectRatio: ImageAspectRatio = 'auto'
) => {
  return submitImageEditJob(buildWorkflowInstruction(prompt), snapshot, undefined, aspectRatio);
};

export const generateWorkflowImage = async (
  prompt: string,
  snapshot: string,
  aspectRatio: ImageAspectRatio = 'auto'
) => {
  const instruction = buildWorkflowInstruction(prompt);

  const config = getApiConfig();
  if (!isGeminiModel(config.model)) {
    const job = await submitImageEditJob(instruction, snapshot, undefined, aspectRatio);
    return waitForImageJob(job.jobId);
  }

  const parts = [
    {
      inline_data: {
        data: extractBase64(snapshot),
        mime_type: "image/png"
      }
    },
    {
      text: `${instruction}

请直接返回图像数据流（Base64），不要输出任何解释文字。`
    }
  ];

  const data = await callGeminiApi(parts, getGeminiImageConfig(aspectRatio));
  return extractImageFromResponse(data);
};

export const editImage = async (
  prompt: string,
  imageDataUrl: string,
  maskDataUrl?: string,
  aspectRatio: ImageAspectRatio = 'auto'
) => {
  const config = getApiConfig();
  if (!isGeminiModel(config.model)) {
    const job = await submitImageEditJob(prompt, imageDataUrl, maskDataUrl, aspectRatio);
    return waitForImageJob(job.jobId);
  }

  if (maskDataUrl) {
    throw new Error('当前 Gemini 图像模型不支持 mask 局部重绘，请切换到 gpt-image-2');
  }

  return generateWorkflowImage(prompt, imageDataUrl, aspectRatio);
};

export const generateImage = async (
  prompt: string,
  style: string = 'none',
  aspectRatio: ImageAspectRatio = 'auto'
) => {
  const config = getApiConfig();
  if (!isGeminiModel(config.model)) {
    const job = await submitImageGenerationJob(prompt, aspectRatio);
    return waitForImageJob(job.jobId);
  }

  const parts = [{ text: prompt }];
  const data = await callGeminiApi(parts, getGeminiImageConfig(aspectRatio));
  return extractImageFromResponse(data);
};

export const generateBrainstorm = async (topic: string, description: string) => {
  const parts = [{ text: `Brainstorm ideas for: "${topic}". Description: ${description}. Markdown.` }];
  const data = await callGeminiApi(parts);

  if (data.candidates?.[0]?.content?.parts) {
    for (const part of data.candidates[0].content.parts) {
      if (part.text) return part.text;
    }
  }
  throw new Error('未能获取响应');
};
