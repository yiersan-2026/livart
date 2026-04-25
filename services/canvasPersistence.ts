import { CanvasItem, ChatMessage } from '../types';

export interface CanvasPersistenceState {
  items: CanvasItem[];
  messages: ChatMessage[];
  viewport: {
    zoom: number;
    pan: { x: number; y: number };
  };
  selectedIds: string[];
}

export interface CanvasProject {
  id: string;
  title: string;
  createdAt?: string;
  updatedAt?: string;
  revision?: number;
}

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

interface CanvasResponse {
  id: string;
  title: string;
  state: Partial<CanvasPersistenceState> | null;
  createdAt?: string;
  updatedAt?: string;
  revision?: number;
  queued?: boolean;
}

interface AssetResponse {
  urlPath: string;
}

let currentCanvasId: string | null = null;
const dataUrlUploadCache = new Map<string, Promise<string>>();

const isDataImageUrl = (value: unknown): value is string => {
  return typeof value === 'string' && value.startsWith('data:image/');
};

const unwrapApiResponse = async <T>(response: Response): Promise<T> => {
  const payload = await response.json().catch(() => null) as ApiResponse<T> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.error?.message || `后端请求失败：${response.status}`);
  }
  if (payload.data === undefined) {
    throw new Error('后端响应为空');
  }
  return payload.data;
};

const getExtensionFromMime = (mimeType: string) => {
  switch (mimeType) {
    case 'image/jpeg':
    case 'image/jpg':
      return '.jpg';
    case 'image/webp':
      return '.webp';
    case 'image/gif':
      return '.gif';
    case 'image/svg+xml':
      return '.svg';
    default:
      return '.png';
  }
};

const uploadDataImage = async (dataUrl: string, filenameSeed: string) => {
  const cached = dataUrlUploadCache.get(dataUrl);
  if (cached) return cached;

  const uploadPromise = (async () => {
    const blob = await fetch(dataUrl).then(response => response.blob());
    const filename = `${filenameSeed}${getExtensionFromMime(blob.type || 'image/png')}`;
    const file = new File([blob], filename, { type: blob.type || 'image/png' });
    const formData = new FormData();
    formData.append('file', file);
    if (currentCanvasId) {
      formData.append('canvasId', currentCanvasId);
    }

    const response = await fetch('/api/assets', {
      method: 'POST',
      body: formData
    });
    const asset = await unwrapApiResponse<AssetResponse>(response);
    return asset.urlPath;
  })();

  dataUrlUploadCache.set(dataUrl, uploadPromise);
  return uploadPromise;
};

const persistImageValue = async (value: string | undefined, filenameSeed: string) => {
  if (!isDataImageUrl(value)) return value;
  return uploadDataImage(value, filenameSeed);
};

const persistItemAssets = async (item: CanvasItem): Promise<CanvasItem> => {
  const [content, drawingData, maskData, compositeImage, layers] = await Promise.all([
    persistImageValue(item.content, `${item.id}-content`),
    persistImageValue(item.drawingData, `${item.id}-drawing`),
    persistImageValue(item.maskData, `${item.id}-mask`),
    persistImageValue(item.compositeImage, `${item.id}-composite`),
    Promise.all((item.layers || []).map(async layer => ({
      ...layer,
      content: layer.type === 'image'
        ? await persistImageValue(layer.content, `${item.id}-${layer.id}`)
        : layer.content
    })))
  ]);

  return {
    ...item,
    content: content || item.content,
    drawingData,
    maskData,
    compositeImage,
    layers
  };
};

export interface CanvasLoadResult {
  project: CanvasProject;
  state: CanvasPersistenceState;
}

const createEmptyState = (): CanvasPersistenceState => ({
  items: [],
  messages: [],
  viewport: {
    zoom: 1,
    pan: {
      x: window.innerWidth / 4,
      y: window.innerHeight / 4
    }
  },
  selectedIds: []
});

const toCanvasProject = (canvas: CanvasResponse | CanvasProject): CanvasProject => ({
  id: canvas.id,
  title: canvas.title || '未命名项目',
  createdAt: canvas.createdAt,
  updatedAt: canvas.updatedAt,
  revision: canvas.revision
});

const normalizeLoadedState = (state: Partial<CanvasPersistenceState> | null): CanvasPersistenceState => {
  if (!state || !Array.isArray(state.items)) return createEmptyState();
  return {
    items: state.items,
    messages: Array.isArray(state.messages) ? state.messages : [],
    viewport: {
      zoom: typeof state.viewport?.zoom === 'number' ? state.viewport.zoom : 1,
      pan: {
        x: typeof state.viewport?.pan?.x === 'number' ? state.viewport.pan.x : window.innerWidth / 4,
        y: typeof state.viewport?.pan?.y === 'number' ? state.viewport.pan.y : window.innerHeight / 4
      }
    },
    selectedIds: []
  };
};

export const listCanvasProjects = async () => {
  const response = await fetch('/api/canvases', {
    headers: { Accept: 'application/json' }
  });
  const projects = await unwrapApiResponse<CanvasProject[]>(response);
  return projects.map(toCanvasProject);
};

export const createCanvasProject = async (title: string) => {
  const response = await fetch('/api/canvases', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json'
    },
    body: JSON.stringify({ title })
  });
  const canvas = await unwrapApiResponse<CanvasResponse>(response);
  return {
    project: toCanvasProject(canvas),
    state: normalizeLoadedState(canvas.state)
  };
};

export const loadCanvasProject = async (canvasId: string): Promise<CanvasLoadResult> => {
  const response = await fetch(`/api/canvases/${canvasId}`, {
    headers: { Accept: 'application/json' }
  });
  const canvas = await unwrapApiResponse<CanvasResponse>(response);
  currentCanvasId = canvas.id;
  return {
    project: toCanvasProject(canvas),
    state: normalizeLoadedState(canvas.state)
  };
};

export const loadCurrentCanvas = async () => {
  const projects = await listCanvasProjects();
  const firstProject = projects[0];
  if (!firstProject) {
    return createCanvasProject('默认画布');
  }
  return loadCanvasProject(firstProject.id);
};

export const saveCanvasProject = async (
  canvasId: string,
  state: CanvasPersistenceState,
  clientRevision: number,
  title: string
) => {
  currentCanvasId = canvasId;
  const persistentItems = await Promise.all(state.items.map(persistItemAssets));
  const persistentState: CanvasPersistenceState = {
    ...state,
    items: persistentItems,
    selectedIds: []
  };

  const response = await fetch(`/api/canvases/${canvasId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json'
    },
    body: JSON.stringify({
      title,
      state: persistentState,
      clientRevision
    })
  });

  const canvas = await unwrapApiResponse<CanvasResponse>(response);
  currentCanvasId = canvas.id;
  return persistentState;
};

export const saveCurrentCanvas = async (state: CanvasPersistenceState, clientRevision: number) => {
  const canvasId = currentCanvasId;
  if (!canvasId) {
    throw new Error('当前项目未加载，不能保存画布');
  }
  return saveCanvasProject(canvasId, state, clientRevision, '默认画布');
};
