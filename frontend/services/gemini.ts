import { authHeaders, getStoredAuthSession } from './auth';

const REQUEST_TIMEOUT_MS = 10 * 60 * 1000;
const IMAGE_JOB_STATUS_URL = '/api/image-jobs';
const IMAGE_JOB_WS_PATH = '/ws/image-jobs';

export interface ImagePromptMetadata {
  originalPrompt?: string;
  optimizedPrompt?: string;
}

export interface ImageGenerationResult extends ImagePromptMetadata {
  image: string;
  upstreamStatus?: number;
  requestId?: string;
}

export interface ImageJobStatus extends ImagePromptMetadata {
  jobId: string;
  status: 'queued' | 'running' | 'completed' | 'error';
  response?: unknown;
  error?: unknown;
  createdAt?: number;
  updatedAt?: number;
  upstreamStatus?: number;
  requestId?: string;
  attempts?: number;
  queuePosition?: number;
  maxConcurrentJobs?: number;
  queued?: boolean;
}

interface WaitForImageJobOptions {
  onStatus?: (job: ImageJobStatus) => void;
  onConnectionState?: (state: 'connected' | 'reconnecting') => void;
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

const getImageJobWebSocketUrl = () => {
  if (typeof window === 'undefined') return '';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${IMAGE_JOB_WS_PATH}`;
};

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
  const safeMessage = Boolean(payload && typeof payload === 'object'
    ? (payload as Record<string, unknown>).safeMessage
    : false);

  if (safeMessage || code === 'POSSIBLE_CONTENT_POLICY_BLOCKED' || type === 'content_policy') {
    return message;
  }

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

export const getImageJobQueueMessage = (job: ImageJobStatus, label = '图片任务') => {
  const queuePosition = Number(job.queuePosition || 0);
  if (job.status !== 'queued' || queuePosition <= 0) {
    return '';
  }

  const maxConcurrentJobs = Number(job.maxConcurrentJobs || 16);
  if (queuePosition <= 1) {
    return `${label}排队中：正在等待空闲生成通道，最多同时处理 ${maxConcurrentJobs} 个任务。`;
  }
  return `${label}排队中：前面还有 ${queuePosition - 1} 个任务，最多同时处理 ${maxConcurrentJobs} 个任务。`;
};

const waitForImageJobByWebSocket = async (jobId: string, options: WaitForImageJobOptions = {}) => {
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
    let isReconnecting = false;
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
      isReconnecting = true;
      options.onConnectionState?.('reconnecting');
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
      options.onStatus?.(job);
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
        if (isReconnecting) {
          isReconnecting = false;
          options.onConnectionState?.('connected');
        }
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

export const waitForImageJob = async (jobId: string, options: WaitForImageJobOptions = {}) => {
  return waitForImageJobByWebSocket(jobId, options);
};
