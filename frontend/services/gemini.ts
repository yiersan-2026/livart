import { getApiConfig, hasApiConfig } from './config';
import { authHeaders, getStoredAuthSession } from './auth';
import type { ImageAspectRatio } from '../types';
import { aspectRatioToGeminiAspectRatio } from './imageSizing';

const REQUEST_TIMEOUT_MS = 10 * 60 * 1000;
const TEXT_TO_IMAGE_PROXY_URL = '/api/images/generations';
const IMAGE_TO_IMAGE_PROXY_URL = '/api/images/edits';
const TEXT_TO_IMAGE_JOB_URL = '/api/image-jobs/generations';
const IMAGE_TO_IMAGE_JOB_URL = '/api/image-jobs/edits';
const IMAGE_JOB_STATUS_URL = '/api/image-jobs';
const IMAGE_JOB_WS_PATH = '/ws/image-jobs';
const ORIGINAL_PROMPT_HEADER = 'X-Livart-Original-Prompt-B64';
const OPTIMIZED_PROMPT_HEADER = 'X-Livart-Optimized-Prompt-B64';

const isGeminiModel = (model: string) => model.startsWith('gemini-');

export interface ImagePromptMetadata {
  originalPrompt?: string;
  optimizedPrompt?: string;
}

export interface ImageGenerationResult extends ImagePromptMetadata {
  image: string;
  upstreamStatus?: number;
  requestId?: string;
}

export interface ImageJobSubmission {
  jobId: string;
  status: 'queued' | 'running' | 'completed' | 'error';
  originalPrompt?: string;
  optimizedPrompt?: string;
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

export interface ImageEditAssetOptions {
  imageAssetId?: string;
  referenceAssetIds?: string[];
  imageContext?: string;
  promptOptimizationMode?: 'image-remover' | 'background-removal';
}

class ImageJobWebSocketUnavailableError extends Error {
  constructor(message: string) {
    super(message);
    this.name = 'ImageJobWebSocketUnavailableError';
  }
}

const extractBase64 = (data: string) => data.includes(',') ? data.split(',')[1] : data;

const normalizeImageMimeType = (value: unknown, fallback = 'image/png') => {
  if (typeof value !== 'string') return fallback;
  const normalized = value.trim().toLowerCase();
  if (normalized === 'image/jpg') return 'image/jpeg';
  return ['image/png', 'image/jpeg', 'image/webp', 'image/gif'].includes(normalized)
    ? normalized
    : fallback;
};

const inferImageMimeTypeFromBase64 = (base64: string) => {
  try {
    const binary = atob(extractBase64(base64).slice(0, 96));
    const byteAt = (index: number) => binary.charCodeAt(index);
    if (byteAt(0) === 0x89 && binary.slice(1, 4) === 'PNG') return 'image/png';
    if (byteAt(0) === 0xff && byteAt(1) === 0xd8 && byteAt(2) === 0xff) return 'image/jpeg';
    if (binary.slice(0, 4) === 'RIFF' && binary.slice(8, 12) === 'WEBP') return 'image/webp';
    if (binary.slice(0, 3) === 'GIF') return 'image/gif';
  } catch {
    return '';
  }
  return '';
};

const getImageMimeTypeFromPayload = (payload: Record<string, unknown> | undefined, base64: string) => {
  return normalizeImageMimeType(
    payload?.mime_type || payload?.mimeType || payload?.content_type || payload?.contentType || payload?.type,
    inferImageMimeTypeFromBase64(base64) || 'image/png'
  );
};

const imageBase64ToDataUrl = (base64: string, payload?: Record<string, unknown>) => {
  if (base64.startsWith('data:image/')) return base64;
  return `data:${getImageMimeTypeFromPayload(payload, base64)};base64,${extractBase64(base64)}`;
};

const getImageExtensionFromMime = (mimeType: string) => {
  switch (normalizeImageMimeType(mimeType)) {
    case 'image/jpeg':
      return '.jpg';
    case 'image/webp':
      return '.webp';
    case 'image/gif':
      return '.gif';
    default:
      return '.png';
  }
};

const dataUrlToBlob = async (dataUrl: string) => {
  const response = await fetch(dataUrl);
  return response.blob();
};

const appendImageBlob = async (formData: FormData, dataUrl: string, filenameSeed: string) => {
  const blob = await dataUrlToBlob(dataUrl);
  formData.append('image', blob, `${filenameSeed}${getImageExtensionFromMime(blob.type || 'image/png')}`);
};

const appendImageEditImages = async (
  formData: FormData,
  imageDataUrl: string,
  referenceImageDataUrls: string[],
  assetOptions: ImageEditAssetOptions = {}
) => {
  if (assetOptions.imageAssetId) {
    formData.append('imageAssetId', assetOptions.imageAssetId);
  } else {
    await appendImageBlob(formData, imageDataUrl, 'canvas');
  }

  const referenceAssetIds = assetOptions.referenceAssetIds || [];
  for (const [index, referenceImageDataUrl] of referenceImageDataUrls.entries()) {
    const referenceAssetId = referenceAssetIds[index];
    if (referenceAssetId) {
      formData.append('referenceAssetId', referenceAssetId);
    } else {
      await appendImageBlob(formData, referenceImageDataUrl, `reference-${index + 1}`);
    }
  }
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

const imageProxyHeaders = () => ({
  'Accept': 'application/json',
  ...authHeaders()
});

const imageEditProxyHeaders = () => ({
  'Accept': 'application/json',
  ...authHeaders()
});

const getImageJobWebSocketUrl = () => {
  if (typeof window === 'undefined') return '';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${IMAGE_JOB_WS_PATH}`;
};

const aspectRatioToPromptInstruction = (aspectRatio: ImageAspectRatio) => {
  switch (aspectRatio) {
    case '1:1':
      return '画幅比例要求：最终输出为 1:1 方图构图，不要添加白边、相框或多余留白来凑比例。';
    case '4:3':
      return '画幅比例要求：最终输出为 4:3 横向标准构图，不要添加白边、相框或多余留白来凑比例。';
    case '3:4':
      return '画幅比例要求：最终输出为 3:4 竖向标准构图，不要添加白边、相框或多余留白来凑比例。';
    case '16:9':
      return '画幅比例要求：最终输出为 16:9 横向宽屏构图，不要添加白边、相框或多余留白来凑比例。';
    case '9:16':
      return '画幅比例要求：最终输出为 9:16 竖向手机屏幕构图，不要添加白边、相框或多余留白来凑比例。';
    default:
      return '';
  }
};

const appendAspectRatioToPrompt = (prompt: string, aspectRatio: ImageAspectRatio) => {
  const instruction = aspectRatioToPromptInstruction(aspectRatio);
  if (!instruction || prompt.includes('画幅比例要求')) return prompt;
  const trimmedPrompt = prompt.trim();
  if (!trimmedPrompt) return instruction;
  const separator = /[。.!！？?]$/.test(trimmedPrompt) ? '\n' : '。\n';
  return `${trimmedPrompt}${separator}${instruction}`;
};

const decodePromptHeader = (value: string | null) => {
  if (!value) return '';
  try {
    const binary = window.atob(value);
    const bytes = Uint8Array.from(binary, char => char.charCodeAt(0));
    return new TextDecoder().decode(bytes);
  } catch {
    return '';
  }
};

const getPromptMetadataFromHeaders = (response: Response): ImagePromptMetadata => ({
  originalPrompt: decodePromptHeader(response.headers.get(ORIGINAL_PROMPT_HEADER)) || undefined,
  optimizedPrompt: decodePromptHeader(response.headers.get(OPTIMIZED_PROMPT_HEADER)) || undefined
});

const getPromptMetadataFromPayload = (payload: unknown): ImagePromptMetadata => {
  if (!payload || typeof payload !== 'object') return {};
  const data = payload as Record<string, unknown>;
  const nested = data.livartPromptMetadata && typeof data.livartPromptMetadata === 'object'
    ? data.livartPromptMetadata as Record<string, unknown>
    : data;
  return {
    originalPrompt: typeof nested.originalPrompt === 'string' ? nested.originalPrompt : undefined,
    optimizedPrompt: typeof nested.optimizedPrompt === 'string' ? nested.optimizedPrompt : undefined
  };
};

const mergePromptMetadata = (...metadatas: ImagePromptMetadata[]) => metadatas.reduce<ImagePromptMetadata>((merged, metadata) => ({
  originalPrompt: metadata.originalPrompt || merged.originalPrompt,
  optimizedPrompt: metadata.optimizedPrompt || merged.optimizedPrompt
}), {});

const callGeminiApi = async (..._args: unknown[]) => {
  throw new Error('当前版本不允许前端直连上游 AI，请使用后端代理模型 gpt-image-2');
};

const callImageGenerationApi = async (prompt: string, aspectRatio: ImageAspectRatio = 'auto') => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const finalPrompt = appendAspectRatioToPrompt(prompt, aspectRatio);
  const response = await fetchWithTimeout(TEXT_TO_IMAGE_PROXY_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      ...imageProxyHeaders()
    },
    body: JSON.stringify({
      model: config.model,
      prompt: finalPrompt
    })
  }, '文生图请求超过 10 分钟仍未完成，请稍后重试');

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`API 请求失败: ${error}`);
  }

  const data = await response.json();
  return extractImageResultFromResponse(data, getPromptMetadataFromHeaders(response));
};

const callImageEditApi = async (
  prompt: string,
  imageDataUrl: string,
  maskDataUrl?: string,
  aspectRatio: ImageAspectRatio = 'auto',
  referenceImageDataUrls: string[] = [],
  assetOptions: ImageEditAssetOptions = {}
) => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const finalPrompt = appendAspectRatioToPrompt(prompt, aspectRatio);
  const formData = new FormData();
  formData.append('model', config.model);
  formData.append('prompt', finalPrompt);
  if (assetOptions.promptOptimizationMode) {
    formData.append('promptOptimizationMode', assetOptions.promptOptimizationMode);
  }
  if (assetOptions.imageContext?.trim()) {
    formData.append('imageContext', assetOptions.imageContext.trim());
  }
  await appendImageEditImages(formData, imageDataUrl, referenceImageDataUrls, assetOptions);
  if (maskDataUrl) {
    formData.append('mask', await dataUrlToBlob(maskDataUrl), 'mask.png');
  }

  const response = await fetchWithTimeout(IMAGE_TO_IMAGE_PROXY_URL, {
    method: 'POST',
    headers: imageEditProxyHeaders(),
    body: formData
  }, '图生图请求超过 10 分钟仍未完成，请稍后重试');

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`API 请求失败: ${error}`);
  }

  const data = await response.json();
  return extractImageResultFromResponse(data, getPromptMetadataFromHeaders(response));
};

const extractImageFromResponse = (data: any): string => {
  const imageData = data.data?.[0] || data.images?.[0];
  if (imageData?.b64_json) {
    return imageBase64ToDataUrl(imageData.b64_json, imageData);
  }
  if (imageData?.url) {
    return imageData.url;
  }

  if (data.candidates?.[0]?.content?.parts) {
    for (const part of data.candidates[0].content.parts) {
      const inlineData = part.inlineData || part.inline_data;
      if (inlineData?.data) {
        return imageBase64ToDataUrl(inlineData.data, inlineData);
      }
    }
  }
  throw new Error('未能从响应中获取图像数据');
};

const extractImageResultFromResponse = (
  data: unknown,
  metadata: ImagePromptMetadata = {}
): ImageGenerationResult => ({
  image: extractImageFromResponse(data),
  ...mergePromptMetadata(getPromptMetadataFromPayload(data), metadata)
});

const assertSuccessfulImageJob = (job: ImageJobStatus) => {
  const upstreamStatus = Number(job.upstreamStatus || 0);
  if (job.status === 'error' || upstreamStatus >= 400) {
    throw new Error(extractImageJobError(job.error, job));
  }
};

const extractImageResultFromJob = (job: ImageJobStatus): ImageGenerationResult => {
  assertSuccessfulImageJob(job);
  return {
    ...extractImageResultFromResponse(job.response, job),
    ...mergePromptMetadata(getPromptMetadataFromPayload(job.response), job),
    upstreamStatus: job.upstreamStatus,
    requestId: job.requestId
  };
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
  const finalPrompt = appendAspectRatioToPrompt(prompt, aspectRatio);
  const response = await fetch(TEXT_TO_IMAGE_JOB_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({
      model: config.model,
      prompt: finalPrompt
    })
  });

  const data = await response.json().catch(() => null);
  if (!response.ok || !data?.jobId) {
    throw new Error(extractApiErrorMessage(data) || `图片任务提交失败：${response.status}`);
  }
  return data;
};

export const submitImageEditJob = async (
  prompt: string,
  imageDataUrl: string,
  maskDataUrl?: string,
  aspectRatio: ImageAspectRatio = 'auto',
  referenceImageDataUrls: string[] = [],
  assetOptions: ImageEditAssetOptions = {}
): Promise<ImageJobSubmission> => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const finalPrompt = appendAspectRatioToPrompt(prompt, aspectRatio);
  const formData = new FormData();
  formData.append('model', config.model);
  formData.append('prompt', finalPrompt);
  if (assetOptions.promptOptimizationMode) {
    formData.append('promptOptimizationMode', assetOptions.promptOptimizationMode);
  }
  if (assetOptions.imageContext?.trim()) {
    formData.append('imageContext', assetOptions.imageContext.trim());
  }
  await appendImageEditImages(formData, imageDataUrl, referenceImageDataUrls, assetOptions);
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
    throw new Error(extractApiErrorMessage(data) || `图片任务提交失败：${response.status}`);
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

const waitForImageJobByWebSocket = async (jobId: string) => {
  const session = getStoredAuthSession();
  const socketUrl = getImageJobWebSocketUrl();
  if (!session?.token || !socketUrl || typeof WebSocket === 'undefined') {
    throw new ImageJobWebSocketUnavailableError('图片任务 WebSocket 不可用');
  }

  return new Promise<ImageGenerationResult>((resolve, reject) => {
    let settled = false;
    let activeSocket: WebSocket | null = null;
    let reconnectTimerId: number | null = null;
    let reconnectAttempts = 0;
    const startedAt = Date.now();
    const timeoutId = window.setTimeout(() => {
      finishReject(new Error('图片任务超过 10 分钟仍未完成，请稍后刷新页面查看'));
    }, REQUEST_TIMEOUT_MS);
    const pingIntervalId = window.setInterval(() => {
      if (activeSocket?.readyState === WebSocket.OPEN) {
        activeSocket.send(JSON.stringify({ type: 'ping' }));
      }
    }, 25 * 1000);

    const cleanup = () => {
      window.clearTimeout(timeoutId);
      window.clearInterval(pingIntervalId);
      if (reconnectTimerId !== null) {
        window.clearTimeout(reconnectTimerId);
        reconnectTimerId = null;
      }
      if (activeSocket) {
        activeSocket.onopen = null;
        activeSocket.onmessage = null;
        activeSocket.onerror = null;
        activeSocket.onclose = null;
        if (activeSocket.readyState === WebSocket.OPEN || activeSocket.readyState === WebSocket.CONNECTING) {
          activeSocket.close();
        }
        activeSocket = null;
      }
    };

    const finishResolve = (value: ImageGenerationResult) => {
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

    const scheduleReconnect = () => {
      if (settled) return;
      const elapsedMs = Date.now() - startedAt;
      if (elapsedMs >= REQUEST_TIMEOUT_MS) {
        finishReject(new Error('图片任务 WebSocket 多次断开且已超时，请稍后刷新页面查看'));
        return;
      }

      reconnectAttempts += 1;
      const reconnectDelayMs = Math.min(5000, 800 + reconnectAttempts * 700);
      reconnectTimerId = window.setTimeout(() => {
        reconnectTimerId = null;
        connectSocket();
      }, reconnectDelayMs);
    };

    const handleJobMessage = (job: ImageJobStatus) => {
      if (job.status === 'completed') {
        try {
          finishResolve(extractImageResultFromJob(job));
        } catch (error) {
          finishReject(error instanceof Error ? error : new Error('未能从响应中获取图像数据'));
        }
        return;
      }

      if (job.status === 'error') {
        finishReject(new Error(extractImageJobError(job.error, job)));
      }
    };

    const connectSocket = () => {
      if (settled) return;
      const socket = new WebSocket(socketUrl);
      activeSocket = socket;

      socket.onopen = () => {
        reconnectAttempts = 0;
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

        handleJobMessage(message.job);
      };

      socket.onerror = () => {
        if (socket.readyState !== WebSocket.CLOSED && socket.readyState !== WebSocket.CLOSING) {
          socket.close();
        }
      };

      socket.onclose = () => {
        if (activeSocket === socket) {
          activeSocket = null;
          scheduleReconnect();
        }
      };
    };

    connectSocket();
  });
};

export const waitForImageJob = async (jobId: string) => {
  return waitForImageJobByWebSocket(jobId);
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
  return {
    image: data.image,
    originalPrompt: prompt,
    optimizedPrompt: instruction || data.optimizedPrompt
  };
};

export const editImage = async (
  prompt: string,
  imageDataUrl: string,
  maskDataUrl?: string,
  aspectRatio: ImageAspectRatio = 'auto',
  referenceImageDataUrls: string[] = [],
  assetOptions: ImageEditAssetOptions = {}
) => {
  const config = getApiConfig();
  if (!isGeminiModel(config.model)) {
    const job = await submitImageEditJob(prompt, imageDataUrl, maskDataUrl, aspectRatio, referenceImageDataUrls, assetOptions);
    return waitForImageJob(job.jobId);
  }

  if (maskDataUrl) {
    throw new Error('当前 Gemini 图像模型不支持 mask 局部重绘，请切换到 gpt-image-2');
  }
  if (referenceImageDataUrls.length > 0) {
    throw new Error('当前 Gemini 图像模型暂不支持多参考图编辑，请切换到 gpt-image-2');
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
  return {
    image: data.image,
    originalPrompt: prompt,
    optimizedPrompt: data.optimizedPrompt || prompt
  };
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
