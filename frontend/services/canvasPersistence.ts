import { CanvasItem, ChatMessage } from '../types';
import { authHeaders } from './auth';

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
  id: string;
  urlPath: string;
  previewUrlPath?: string;
  thumbnailUrlPath?: string;
}

let currentCanvasId: string | null = null;
const dataUrlUploadCache = new Map<string, Promise<AssetResponse>>();

export const resetCanvasPersistenceSession = () => {
  currentCanvasId = null;
  dataUrlUploadCache.clear();
};

const isDataImageUrl = (value: unknown): value is string => {
  return typeof value === 'string' && value.startsWith('data:image/');
};

export const getAssetIdFromImageUrl = (value: unknown) => {
  if (typeof value !== 'string') return '';
  const match = value.match(/\/api\/assets\/([^/]+)\/(?:content|preview|thumbnail)(?:[?#].*)?$/);
  if (!match) return '';
  try {
    return decodeURIComponent(match[1]);
  } catch {
    return match[1];
  }
};

export const getCanvasItemAssetId = (item: CanvasItem) => {
  return item.assetId || getAssetIdFromImageUrl(item.content) || getAssetIdFromImageUrl(item.previewContent);
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
      headers: {
        ...authHeaders()
      },
      body: formData
    });
    const asset = await unwrapApiResponse<AssetResponse>(response);
    return asset;
  })();

  dataUrlUploadCache.set(dataUrl, uploadPromise);
  return uploadPromise;
};

const persistRawImageValue = async (value: string | undefined, filenameSeed: string) => {
  if (!isDataImageUrl(value)) return value;
  return (await uploadDataImage(value, filenameSeed)).urlPath;
};

const persistCanvasImageValue = async (item: CanvasItem) => {
  if (!isDataImageUrl(item.content)) {
    return {
      content: item.content,
      assetId: getCanvasItemAssetId(item) || undefined,
      previewContent: item.previewContent,
      thumbnailContent: item.thumbnailContent
    };
  }

  const asset = await uploadDataImage(item.content, `${item.id}-content`);
  return {
    content: asset.urlPath,
    assetId: asset.id,
    previewContent: asset.previewUrlPath || asset.urlPath,
    thumbnailContent: asset.thumbnailUrlPath || asset.previewUrlPath || asset.urlPath
  };
};

export const ensureCanvasImageAsset = async (item: CanvasItem) => {
  if (item.type !== 'image') {
    throw new Error('只有图片元素可以作为编辑素材');
  }

  const currentAssetId = getCanvasItemAssetId(item);
  if (currentAssetId) {
    return {
      ...item,
      assetId: currentAssetId
    };
  }

  if (!isDataImageUrl(item.content)) {
    throw new Error('图片缺少可用的资源 ID，无法提交编辑');
  }

  const imageContent = await persistCanvasImageValue(item);
  return {
    ...item,
    content: imageContent.content,
    assetId: imageContent.assetId,
    previewContent: imageContent.previewContent,
    thumbnailContent: imageContent.thumbnailContent
  };
};

const normalizeTransientItemState = (item: CanvasItem): CanvasItem | null => {
  if (item.status !== 'loading') return item;
  if (item.type === 'image' && item.imageJobId) return item;
  if (item.type === 'image' && !item.content) return null;
  return {
    ...item,
    status: 'completed'
  };
};

const persistItemAssets = async (item: CanvasItem): Promise<CanvasItem> => {
  const [imageContent, drawingData, maskData, compositeImage, layers] = await Promise.all([
    item.type === 'image' ? persistCanvasImageValue(item) : Promise.resolve({
      content: item.content,
      previewContent: item.previewContent,
      thumbnailContent: item.thumbnailContent
    }),
    persistRawImageValue(item.drawingData, `${item.id}-drawing`),
    persistRawImageValue(item.maskData, `${item.id}-mask`),
    persistRawImageValue(item.compositeImage, `${item.id}-composite`),
    Promise.all((item.layers || []).map(async layer => ({
      ...layer,
      content: layer.type === 'image'
        ? await persistRawImageValue(layer.content, `${item.id}-${layer.id}`)
        : layer.content
    })))
  ]);

  return {
    ...item,
    content: imageContent.content || item.content,
    assetId: imageContent.assetId || item.assetId,
    previewContent: imageContent.previewContent,
    thumbnailContent: imageContent.thumbnailContent,
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
    items: state.items
      .map(normalizeTransientItemState)
      .filter((item): item is CanvasItem => item !== null),
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
    headers: {
      Accept: 'application/json',
      ...authHeaders()
    }
  });
  const projects = await unwrapApiResponse<CanvasProject[]>(response);
  return projects.map(toCanvasProject);
};

export const createCanvasProject = async (title: string) => {
  const response = await fetch('/api/canvases', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
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
    headers: {
      Accept: 'application/json',
      ...authHeaders()
    }
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
  const durableItems = state.items
    .map(normalizeTransientItemState)
    .filter((item): item is CanvasItem => item !== null);
  const persistentItems = await Promise.all(durableItems.map(persistItemAssets));
  const persistentState: CanvasPersistenceState = {
    ...state,
    items: persistentItems,
    selectedIds: []
  };

  const response = await fetch(`/api/canvases/${canvasId}`, {
    method: 'PUT',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
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
