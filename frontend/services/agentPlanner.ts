import type { AgentPlan, AgentPlanStep, AgentToolId, CanvasItem, ImageAspectRatio, ImageResolution, ProductPosterAnalysis, ProductPosterRequest } from '../types';
import { authHeaders, getStoredAuthSession } from './auth';
import { getCanvasItemAssetId } from './canvasPersistence';
import { hasUsableImageSource } from './imageSources';
import { getImageReferenceLabel } from './imageReferences';

const AGENT_RUN_URL = '/api/agent/runs';
const AGENT_RUN_STATUS_URL = '/api/agent/runs';
const AGENT_RUN_WS_PATH = '/ws/image-jobs';
const PRODUCT_POSTER_ANALYSIS_URL = '/api/product-posters/analyze';

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

interface AgentPlanImageCandidate {
  id: string;
  name: string;
  index?: number;
  width: number;
  height: number;
  assetId?: string;
}

interface AgentPlanApiResponse {
  allowed?: boolean;
  responseMode?: AgentPlan['responseMode'];
  rejectionMessage?: string;
  answerMessage?: string;
  taskType: AgentPlan['taskType'];
  mode: AgentPlan['mode'];
  count: number;
  baseImageId?: string;
  referenceImageIds?: string[];
  aspectRatio?: ImageAspectRatio;
  summary: string;
  displayTitle?: string;
  displayMessage?: string;
  thinkingSteps?: string[];
  steps?: Array<{
    id: string;
    title: string;
    description: string;
    type: AgentPlan['steps'][number]['type'];
  }>;
  source?: AgentPlan['source'];
}

export interface AgentPlanRequest {
  prompt: string;
  aspectRatio?: ImageAspectRatio;
  imageResolution?: ImageResolution;
  contextImageId?: string;
  requestedEditMode?: 'local-redraw' | 'remover' | 'layer-subject' | 'layer-background' | 'view-change' | 'panorama' | 'product-poster';
  forcedToolId?: AgentToolId;
  externalSkillId?: string;
  productPoster?: ProductPosterRequest;
  images: CanvasItem[];
}

interface AgentRunJobApiResponse {
  jobId: string;
  status: 'queued' | 'running' | 'completed' | 'error';
  originalPrompt?: string;
  optimizedPrompt?: string;
}

interface AgentRunApiResponse {
  allowed?: boolean;
  responseMode?: AgentPlan['responseMode'];
  rejectionMessage?: string;
  answerMessage?: string;
  plan: AgentPlanApiResponse;
  taskType: AgentPlan['taskType'];
  mode: AgentPlan['mode'];
  count: number;
  baseImageId?: string;
  referenceImageIds?: string[];
  aspectRatio?: ImageAspectRatio;
  requestPrompt?: string;
  displayTitle?: string;
  displayMessage?: string;
  jobs?: AgentRunJobApiResponse[];
  source?: AgentPlan['source'];
}

interface AgentRunStatusApiResponse {
  runId: string;
  status: 'running' | 'completed' | 'error';
  response?: AgentRunApiResponse;
  errorMessage?: string;
  errorCode?: string;
  updatedAt?: number;
}

export interface AgentRunRequest extends AgentPlanRequest {
  maskDataUrl?: string;
  clientRunId?: string;
}

export interface AgentRun {
  allowed: boolean;
  responseMode: AgentPlan['responseMode'];
  rejectionMessage: string;
  answerMessage: string;
  plan: AgentPlan;
  taskType: AgentPlan['taskType'];
  mode: AgentPlan['mode'];
  count: number;
  baseImageId: string;
  referenceImageIds: string[];
  aspectRatio: ImageAspectRatio;
  requestPrompt: string;
  displayTitle: string;
  displayMessage: string;
  jobs: AgentRunJobApiResponse[];
  source: AgentPlan['source'];
}

export interface AgentRunStatus {
  runId: string;
  status: 'running' | 'completed' | 'error';
  response?: AgentRun;
  errorMessage: string;
  errorCode: string;
  updatedAt: number;
}

export interface AgentRunProgressEvent {
  stepId: string;
  title: string;
  description: string;
  stepType: AgentPlan['steps'][number]['type'];
  status: AgentPlan['steps'][number]['status'];
  createdAt?: number;
}

interface AgentRunSocketMessage {
  type?: string;
  runId?: string;
  event?: Partial<AgentRunProgressEvent>;
  status?: AgentRunStatus['status'];
  response?: AgentRunApiResponse;
  errorMessage?: string;
  errorCode?: string;
  updatedAt?: number;
  error?: unknown;
}

interface AgentDraftPlanOptions {
  taskType?: AgentPlan['taskType'];
  mode?: AgentPlan['mode'];
  aspectRatio: ImageAspectRatio;
  summary?: string;
  displayTitle?: string;
  displayMessage?: string;
  thinkingSteps?: string[];
  baseImageId?: string;
  referenceImageIds?: string[];
  count?: number;
  steps?: AgentPlanStep[];
}

const buildDefaultAgentDraftSteps = (
  taskType: AgentPlan['taskType'],
  mode: AgentPlan['mode']
): AgentPlanStep[] => {
  if (taskType === 'image-edit') {
    return [
      {
        id: 'understand-demand',
        title: '理解需求',
        description: '理解这次图片编辑需求。',
        type: 'analysis',
        status: 'running'
      },
      {
        id: 'plan-task',
        title: '规划任务',
        description: '整理主图、参考图、局部蒙版和画幅要求。',
        type: 'prompt',
        status: 'pending'
      },
      {
        id: 'create-image-job',
        title: '提交任务',
        description: '创建图片编辑任务。',
        type: 'edit',
        status: 'pending'
      },
      {
        id: 'wait-image-job',
        title: '等待生成',
        description: '等待上游返回编辑结果。',
        type: 'edit',
        status: 'pending'
      }
    ];
  }

  return [
    {
      id: 'understand-demand',
      title: '理解需求',
      description: '理解你的输入内容。',
      type: 'analysis',
      status: 'running'
    }
  ];
};

export const buildAgentDraftPlan = ({
  taskType = 'text-to-image',
  mode = taskType === 'image-edit' ? 'edit' : 'generate',
  aspectRatio,
  summary,
  displayTitle,
  displayMessage,
  thinkingSteps,
  baseImageId = '',
  referenceImageIds = [],
  count = 1,
  steps
}: AgentDraftPlanOptions): AgentPlan => ({
  allowed: true,
  responseMode: 'execute',
  rejectionMessage: '',
  answerMessage: '',
  taskType,
  mode,
  count,
  baseImageId,
  referenceImageIds,
  aspectRatio,
  summary: summary || (taskType === 'image-edit'
    ? '正在理解图片编辑需求...'
    : '正在理解你的需求...'),
  displayTitle: displayTitle || '',
  displayMessage: displayMessage || '',
  thinkingSteps: thinkingSteps || (taskType === 'image-edit' ? [] : ['识别你的意图', '判断任务类型']),
  steps: steps || buildDefaultAgentDraftSteps(taskType, mode),
  source: 'fallback'
});

export const updateAgentPlanStepStatuses = (
  steps: AgentPlanStep[],
  activeStepId?: string,
  terminalStatus?: 'completed' | 'error'
): AgentPlanStep[] => {
  if (steps.length === 0) return steps;
  const activeIndex = activeStepId ? steps.findIndex(step => step.id === activeStepId) : -1;

  if (terminalStatus) {
    return steps.map((step, index) => {
      if (terminalStatus === 'completed') {
        return {
          ...step,
          status: activeIndex === -1 || index <= activeIndex ? 'completed' : 'pending'
        };
      }

      if (activeIndex === -1) {
        return { ...step, status: 'pending' };
      }

      if (index < activeIndex) {
        return { ...step, status: 'completed' };
      }

      if (index === activeIndex) {
        return { ...step, status: 'error' };
      }

      return { ...step, status: 'pending' };
    });
  }

  return steps.map((step, index) => {
    if (activeIndex === -1) {
      return step;
    }

    if (index < activeIndex) {
      return { ...step, status: 'completed' };
    }

    if (index === activeIndex) {
      return { ...step, status: 'running' };
    }

    return { ...step, status: 'pending' };
  });
};

export const applyAgentRunProgressEventToPlan = (
  plan: AgentPlan,
  event: AgentRunProgressEvent
): AgentPlan => {
  const nextStep: AgentPlanStep = {
    id: event.stepId,
    title: event.title,
    description: event.description,
    type: event.stepType,
    status: event.status
  };
  const stepExists = plan.steps.some(step => step.id === event.stepId);
  const steps = stepExists
    ? plan.steps.map(step => step.id === event.stepId ? { ...step, ...nextStep } : step)
    : [...plan.steps, nextStep];
  return {
    ...plan,
    summary: getAgentPlanStatusLine({
      stepId: event.stepId,
      status: event.status,
      taskType: plan.taskType,
      mode: plan.mode,
      fallbackTitle: event.title
    }),
    thinkingSteps: [],
    steps: updateAgentPlanStepStatuses(steps, event.stepId, event.status === 'completed' || event.status === 'error' ? event.status : undefined)
  };
};

export const getAgentPlanCurrentStep = (plan: AgentPlan) => (
  plan.steps.find(step => step.status === 'running')
  || [...plan.steps].reverse().find(step => step.status !== 'pending')
  || plan.steps[0]
  || null
);

export const getAgentPlanModeLabel = (plan: AgentPlan) => {
  if (plan.mode === 'background-removal') return '去背景';
  if (plan.mode === 'remover') return '局部删除';
  if (plan.mode === 'layer-subject' || plan.mode === 'layer-background') return '图层拆分';
  if (plan.mode === 'panorama') return '全景';
  if (plan.mode === 'view-change') return '多角度';
  if (plan.mode === 'product-poster') return '商品详情图';
  if (plan.taskType === 'text-to-image') return '文生图';
  return '单图编辑';
};

export const getAgentPlanCurrentProgressText = (plan: AgentPlan, fallbackText = '') => {
  const currentStep = getAgentPlanCurrentStep(plan);
  if (!currentStep) return fallbackText || plan.summary || '正在处理你的请求';
  return getAgentPlanStatusLine({
    stepId: currentStep.id,
    status: currentStep.status,
    taskType: plan.taskType,
    mode: plan.mode,
    fallbackTitle: currentStep.title
  });
};

const getAgentPlanStatusLine = ({
  stepId,
  status,
  taskType,
  mode,
  fallbackTitle
}: {
  stepId: string;
  status: AgentPlanStep['status'];
  taskType: AgentPlan['taskType'];
  mode: AgentPlan['mode'];
  fallbackTitle: string;
}) => {
  if (status === 'error') return `${fallbackTitle || '当前步骤'}失败了...`;
  if (status === 'completed') {
    if (stepId === 'understand-demand') return '已理解你的需求...';
    if (stepId === 'knowledge-answer') return '已整理 livart 知识库回答...';
    if (stepId === 'research-industry') return '已完成 WebSearch 行业调研...';
    if (stepId === 'plan-poster-set') return '已完成商品详情图规划...';
    if (stepId === 'run-product-posters') return '已提交商品详情图任务...';
    if (stepId === 'create-image-job') return '已提交生图任务...';
    if (stepId === 'wait-image-job') return '已完成图片生成...';
  }

  if (stepId === 'understand-demand') {
    return '正在理解你的需求...';
  }
  if (stepId === 'identify-intent' || stepId === 'analyze-intent') {
    return '正在判断是生图还是对话...';
  }
  if (stepId === 'knowledge-answer') {
    return '正在检索 livart 知识库...';
  }
  if (stepId === 'research-industry') {
    return '正在 WebSearch 行业趋势...';
  }
  if (stepId === 'scope-check') {
    return '正在检查是否属于 livart 能力范围...';
  }
  if (stepId === 'plan-task'
    || stepId === 'identify-images'
    || stepId === 'identify-base'
    || stepId === 'identify-removal-target'
    || stepId === 'identify-layer-subject'
    || stepId === 'identify-layer-background'
    || stepId === 'identify-panorama'
    || stepId === 'identify-view-change'
    || stepId === 'analyze-product') {
    return '正在规划图片任务...';
  }
  if (stepId === 'optimize-prompt'
    || stepId === 'optimize-edit-prompt'
    || stepId === 'optimize-background-removal'
    || stepId === 'optimize-remover'
    || stepId === 'optimize-layer-split'
    || stepId === 'optimize-panorama'
    || stepId === 'optimize-view-change'
    || stepId === 'plan-poster-set') {
    return '正在优化提示词...';
  }
  if (stepId === 'create-image-job'
    || stepId === 'generate-image'
    || stepId === 'run-image-edit'
    || stepId === 'run-background-removal'
    || stepId === 'run-remover'
    || stepId === 'run-layer-split'
    || stepId === 'run-panorama'
    || stepId === 'run-view-change'
    || stepId === 'run-product-posters') {
    return taskType === 'image-edit' || mode !== 'generate'
      ? '正在提交图片编辑任务...'
      : '正在提交生图任务...';
  }
  if (stepId === 'wait-image-job') {
    return '正在等待图片生成...';
  }
  return fallbackTitle ? `正在${fallbackTitle}...` : '正在处理你的请求...';
};

const getAgentRunWebSocketUrl = () => {
  if (typeof window === 'undefined') return '';
  const protocol = window.location.protocol === 'https:' ? 'wss:' : 'ws:';
  return `${protocol}//${window.location.host}${AGENT_RUN_WS_PATH}`;
};

export const createAgentRunClientId = () => {
  if (typeof crypto !== 'undefined' && 'randomUUID' in crypto) {
    return crypto.randomUUID();
  }
  return `agent-${Date.now().toString(36)}-${Math.random().toString(36).slice(2, 10)}`;
};

const normalizeProgressEvent = (event: Partial<AgentRunProgressEvent> | undefined): AgentRunProgressEvent | null => {
  if (!event?.stepId || !event.title) return null;
  const stepType = event.stepType === 'prompt' || event.stepType === 'generate' || event.stepType === 'edit'
    ? event.stepType
    : 'analysis';
  const status = event.status === 'completed' || event.status === 'error' || event.status === 'pending'
    ? event.status
    : 'running';
  return {
    stepId: event.stepId,
    title: event.title,
    description: event.description || '',
    stepType,
    status,
    createdAt: typeof event.createdAt === 'number' ? event.createdAt : Date.now()
  };
};

export const connectAgentRunEvents = async (
  clientRunId: string,
  onEvent: (event: AgentRunProgressEvent) => void,
  onStatus?: (status: AgentRunStatus) => void,
  onConnectionState?: (state: 'connected' | 'reconnecting') => void
): Promise<() => void> => {
  const session = getStoredAuthSession();
  const socketUrl = getAgentRunWebSocketUrl();
  if (!session?.token || !socketUrl || typeof WebSocket === 'undefined') {
    return () => {};
  }

  return new Promise((resolve) => {
    let resolved = false;
    let stopped = false;
    let socket: WebSocket | null = null;
    let pingIntervalId: number | null = null;
    let reconnectTimerId: number | null = null;
    let reconnectAttempts = 0;
    let isReconnecting = false;
    const fallbackTimerId = window.setTimeout(() => finish(), 1800);

    const cleanupSocket = () => {
      if (pingIntervalId !== null) {
        window.clearInterval(pingIntervalId);
        pingIntervalId = null;
      }
      if (socket) {
        socket.onopen = null;
        socket.onmessage = null;
        socket.onerror = null;
        socket.onclose = null;
        if (socket.readyState === WebSocket.OPEN || socket.readyState === WebSocket.CONNECTING) {
          socket.close();
        }
        socket = null;
      }
    };

    const unsubscribe = () => {
      stopped = true;
      if (reconnectTimerId !== null) {
        window.clearTimeout(reconnectTimerId);
        reconnectTimerId = null;
      }
      cleanupSocket();
    };

    const finish = () => {
      if (resolved) return;
      resolved = true;
      window.clearTimeout(fallbackTimerId);
      resolve(unsubscribe);
    };

    const scheduleReconnect = () => {
      if (stopped) return;
      cleanupSocket();
      isReconnecting = true;
      onConnectionState?.('reconnecting');
      finish();
      reconnectAttempts += 1;
      const reconnectDelayMs = Math.min(5000, 800 + reconnectAttempts * 700);
      reconnectTimerId = window.setTimeout(() => {
        reconnectTimerId = null;
        connectSocket();
      }, reconnectDelayMs);
    };

    function connectSocket() {
      if (stopped) return;
      socket = new WebSocket(socketUrl);

      socket.onopen = () => {
        reconnectAttempts = 0;
        if (isReconnecting) {
          isReconnecting = false;
          onConnectionState?.('connected');
        }
        socket?.send(JSON.stringify({
          type: 'auth',
          token: session.token,
          runId: clientRunId
        }));
        pingIntervalId = window.setInterval(() => {
          if (socket?.readyState === WebSocket.OPEN) {
            socket.send(JSON.stringify({ type: 'ping' }));
          }
        }, 25 * 1000);
      };

      socket.onmessage = (message) => {
        let payload: AgentRunSocketMessage;
        try {
          payload = JSON.parse(String(message.data));
        } catch {
          return;
        }

        if (payload.type === 'authenticated') {
          finish();
          return;
        }

        if (payload.type !== 'agent-run-event' || payload.runId !== clientRunId) {
          if (payload.type === 'agent-run-status' && payload.runId === clientRunId) {
            onStatus?.(normalizeAgentRunStatusPayload({
              runId: payload.runId || clientRunId,
              status: payload.status || 'running',
              response: payload.response,
              errorMessage: payload.errorMessage,
              errorCode: payload.errorCode,
              updatedAt: payload.updatedAt
            }, 'auto'));
          }
          return;
        }

        const progressEvent = normalizeProgressEvent(payload.event);
        if (progressEvent) {
          onEvent(progressEvent);
        }
      };

      socket.onerror = () => {
        if (socket?.readyState !== WebSocket.CLOSED && socket?.readyState !== WebSocket.CLOSING) {
          socket.close();
        }
      };
      socket.onclose = () => scheduleReconnect();
    }

    connectSocket();
  });
};

const mapImageCandidates = (images: CanvasItem[]): AgentPlanImageCandidate[] => {
  const completedImages = images.filter(item => item.type === 'image' && item.status === 'completed' && hasUsableImageSource(item));
  return completedImages.map((item, index) => ({
    id: item.id,
    name: getImageReferenceLabel(item),
    index: index + 1,
    width: Math.round(item.width),
    height: Math.round(item.height),
    assetId: getCanvasItemAssetId(item) || ''
  }));
};

const normalizePlan = (payload: AgentPlanApiResponse, aspectRatio: ImageAspectRatio): AgentPlan => ({
  allowed: payload.allowed !== false,
  responseMode: payload.responseMode === 'answer' ? 'answer' : payload.responseMode === 'reject' ? 'reject' : 'execute',
  rejectionMessage: payload.rejectionMessage || '',
  answerMessage: payload.answerMessage || '',
  taskType: payload.taskType || 'text-to-image',
  mode: payload.mode || (payload.taskType === 'image-edit' ? 'edit' : 'generate'),
  count: Math.max(1, Number(payload.count || 1)),
  baseImageId: payload.baseImageId || '',
  referenceImageIds: Array.isArray(payload.referenceImageIds) ? payload.referenceImageIds : [],
  aspectRatio: payload.aspectRatio || aspectRatio,
  summary: payload.summary || '我已理解你的请求，接下来开始执行。',
  displayTitle: payload.displayTitle || '',
  displayMessage: payload.displayMessage || payload.summary || '',
  thinkingSteps: Array.isArray(payload.thinkingSteps) ? payload.thinkingSteps : [],
  steps: Array.isArray(payload.steps)
    ? payload.steps.map((step, index) => ({
      id: step.id || `step-${index + 1}`,
      title: step.title || `步骤 ${index + 1}`,
      description: step.description || '',
      type: step.type || 'analysis',
      status: index === 0 ? 'running' : 'pending'
    }))
    : [],
  source: payload.source === 'tool' ? 'tool' : payload.source === 'fallback' ? 'fallback' : 'ai'
});

function normalizeAgentRunPayload(payload: AgentRunApiResponse, aspectRatio: ImageAspectRatio, fallbackPrompt = ''): AgentRun {
  const plan = normalizePlan(payload.plan, aspectRatio);
  return {
    allowed: payload.allowed !== false,
    responseMode: payload.responseMode === 'answer' ? 'answer' : payload.responseMode === 'reject' ? 'reject' : 'execute',
    rejectionMessage: payload.rejectionMessage || '',
    answerMessage: payload.answerMessage || '',
    plan,
    taskType: payload.taskType || plan.taskType,
    mode: payload.mode || plan.mode,
    count: Math.max(0, Number(payload.count || 0)),
    baseImageId: payload.baseImageId || plan.baseImageId || '',
    referenceImageIds: Array.isArray(payload.referenceImageIds) ? payload.referenceImageIds : plan.referenceImageIds,
    aspectRatio: payload.aspectRatio || plan.aspectRatio,
    requestPrompt: payload.requestPrompt || fallbackPrompt,
    displayTitle: payload.displayTitle || plan.displayTitle,
    displayMessage: payload.displayMessage || plan.displayMessage,
    jobs: Array.isArray(payload.jobs) ? payload.jobs : [],
    source: payload.source === 'tool' ? 'tool' : payload.source === 'fallback' ? 'fallback' : 'ai'
  };
}

function normalizeAgentRunStatusPayload(payload: AgentRunStatusApiResponse, aspectRatio: ImageAspectRatio): AgentRunStatus {
  return {
    runId: payload.runId || '',
    status: payload.status === 'completed' || payload.status === 'error' ? payload.status : 'running',
    response: payload.response ? normalizeAgentRunPayload(payload.response, aspectRatio, payload.response.requestPrompt || '') : undefined,
    errorMessage: payload.errorMessage || '',
    errorCode: payload.errorCode || '',
    updatedAt: typeof payload.updatedAt === 'number' ? payload.updatedAt : Date.now()
  };
}

export const createAgentRun = async (request: AgentRunRequest): Promise<AgentRun> => {
  const aspectRatio = request.aspectRatio || 'auto';
  const imageResolution = request.imageResolution || '2k';
  const response = await fetch(AGENT_RUN_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({
      prompt: request.prompt,
      contextImageId: request.contextImageId || '',
      aspectRatio,
      imageResolution,
      requestedEditMode: request.requestedEditMode || '',
      forcedToolId: request.forcedToolId || '',
      externalSkillId: request.externalSkillId || '',
      productPoster: request.productPoster,
      images: mapImageCandidates(request.images),
      maskDataUrl: request.maskDataUrl || '',
      clientRunId: request.clientRunId || ''
    })
  });

  const payload = await response.json().catch(() => null) as ApiResponse<AgentRunApiResponse> | null;
  if (!response.ok || !payload?.success || !payload.data) {
    throw new Error(payload?.error?.message || `Agent 执行失败：${response.status}`);
  }

  return normalizeAgentRunPayload(payload.data, aspectRatio, request.prompt);
};

export const analyzeProductPosterDescription = async (
  description: string,
  images: CanvasItem[] = [],
  options: {
    latestUserMessage?: string;
    conversationContext?: string;
  } = {}
): Promise<ProductPosterAnalysis> => {
  const response = await fetch(PRODUCT_POSTER_ANALYSIS_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({
      description,
      latestUserMessage: options.latestUserMessage || '',
      conversationContext: options.conversationContext || '',
      images: mapImageCandidates(images)
    })
  });

  const payload = await response.json().catch(() => null) as ApiResponse<ProductPosterAnalysis> | null;
  if (!response.ok || !payload?.success || !payload.data) {
    throw new Error(payload?.error?.message || `产品特征分析失败：${response.status}`);
  }
  return payload.data;
};

export const getAgentRunStatus = async (
  runId: string,
  aspectRatio: ImageAspectRatio = 'auto'
): Promise<AgentRunStatus> => {
  const response = await fetch(`${AGENT_RUN_STATUS_URL}/${encodeURIComponent(runId)}`, {
    headers: {
      Accept: 'application/json',
      ...authHeaders()
    }
  });

  const payload = await response.json().catch(() => null) as ApiResponse<AgentRunStatusApiResponse> | null;
  if (!response.ok || !payload?.success || !payload.data) {
    throw new Error(payload?.error?.message || `Agent 任务状态查询失败：${response.status}`);
  }
  return normalizeAgentRunStatusPayload(payload.data, aspectRatio);
};
