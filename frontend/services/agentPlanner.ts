import type { AgentPlan, AgentPlanStep, CanvasItem, ImageAspectRatio } from '../types';
import { authHeaders, getStoredAuthSession } from './auth';
import { getCanvasItemAssetId } from './canvasPersistence';
import { hasUsableImageSource } from './imageSources';
import { getImageReferenceLabel } from './imageReferences';

const AGENT_RUN_URL = '/api/agent/runs';
const AGENT_RUN_WS_PATH = '/ws/image-jobs';

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
  contextImageId?: string;
  requestedEditMode?: 'local-redraw' | 'remover' | 'layer-subject' | 'layer-background' | 'view-change';
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
) => {
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
  if (plan.mode === 'view-change') return '多角度';
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
  if (stepId === 'scope-check') {
    return '正在检查是否属于 livart 能力范围...';
  }
  if (stepId === 'plan-task'
    || stepId === 'identify-images'
    || stepId === 'identify-base'
    || stepId === 'identify-removal-target'
    || stepId === 'identify-layer-subject'
    || stepId === 'identify-layer-background'
    || stepId === 'identify-view-change') {
    return '正在规划图片任务...';
  }
  if (stepId === 'optimize-prompt'
    || stepId === 'optimize-edit-prompt'
    || stepId === 'optimize-background-removal'
    || stepId === 'optimize-remover'
    || stepId === 'optimize-layer-split'
    || stepId === 'optimize-view-change') {
    return '正在优化提示词...';
  }
  if (stepId === 'create-image-job'
    || stepId === 'generate-image'
    || stepId === 'run-image-edit'
    || stepId === 'run-background-removal'
    || stepId === 'run-remover'
    || stepId === 'run-layer-split'
    || stepId === 'run-view-change') {
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
  onEvent: (event: AgentRunProgressEvent) => void
): Promise<() => void> => {
  const session = getStoredAuthSession();
  const socketUrl = getAgentRunWebSocketUrl();
  if (!session?.token || !socketUrl || typeof WebSocket === 'undefined') {
    return () => {};
  }

  return new Promise((resolve) => {
    let resolved = false;
    let socket: WebSocket | null = new WebSocket(socketUrl);
    let pingIntervalId: number | null = null;
    const fallbackTimerId = window.setTimeout(() => finish(), 1800);

    const cleanup = () => {
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

    const unsubscribe = () => cleanup();

    const finish = () => {
      if (resolved) return;
      resolved = true;
      window.clearTimeout(fallbackTimerId);
      resolve(unsubscribe);
    };

    socket.onopen = () => {
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
        return;
      }

      const progressEvent = normalizeProgressEvent(payload.event);
      if (progressEvent) {
        onEvent(progressEvent);
      }
    };

    socket.onerror = () => finish();
    socket.onclose = () => finish();
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
  source: payload.source === 'fallback' ? 'fallback' : 'ai'
});

export const createAgentRun = async (request: AgentRunRequest): Promise<AgentRun> => {
  const aspectRatio = request.aspectRatio || 'auto';
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
      requestedEditMode: request.requestedEditMode || '',
      images: mapImageCandidates(request.images),
      maskDataUrl: request.maskDataUrl || '',
      clientRunId: request.clientRunId || ''
    })
  });

  const payload = await response.json().catch(() => null) as ApiResponse<AgentRunApiResponse> | null;
  if (!response.ok || !payload?.success || !payload.data) {
    throw new Error(payload?.error?.message || `Agent 执行失败：${response.status}`);
  }

  const plan = normalizePlan(payload.data.plan, aspectRatio);
  return {
    allowed: payload.data.allowed !== false,
    responseMode: payload.data.responseMode === 'answer' ? 'answer' : payload.data.responseMode === 'reject' ? 'reject' : 'execute',
    rejectionMessage: payload.data.rejectionMessage || '',
    answerMessage: payload.data.answerMessage || '',
    plan,
    taskType: payload.data.taskType || plan.taskType,
    mode: payload.data.mode || plan.mode,
    count: Math.max(0, Number(payload.data.count || 0)),
    baseImageId: payload.data.baseImageId || plan.baseImageId || '',
    referenceImageIds: Array.isArray(payload.data.referenceImageIds) ? payload.data.referenceImageIds : plan.referenceImageIds,
    aspectRatio: payload.data.aspectRatio || plan.aspectRatio,
    requestPrompt: payload.data.requestPrompt || request.prompt,
    displayTitle: payload.data.displayTitle || plan.displayTitle,
    displayMessage: payload.data.displayMessage || plan.displayMessage,
    jobs: Array.isArray(payload.data.jobs) ? payload.data.jobs : [],
    source: payload.data.source === 'fallback' ? 'fallback' : 'ai'
  };
};
