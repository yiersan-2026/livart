
import React, { useEffect, useRef, useState } from 'react';
import { PanelRightClose, PanelRight, Settings, FolderPlus, LogOut, Loader2, X, Download, ChevronDown } from 'lucide-react';
import type { CanvasItem, CanvasTool, ChatMessage, ImageAspectRatio } from './types';
import AuthPanel from './components/AuthPanel';
import Canvas from './components/Canvas';
import Sidebar from './components/Sidebar';
import Toolbar from './components/Toolbar';
import ConfigModal from './components/ConfigModal';
import CanvasUtilityDock from './components/CanvasUtilityDock';
import ProjectLinks from './components/ProjectLinks';
import {
  canUseImageJobs,
  editImage,
  generateImage,
  type ImageGenerationResult,
  submitImageEditJob,
  submitImageGenerationJob,
  waitForImageJob
} from './services/gemini';
import {
  DEFAULT_GENERATED_IMAGE_LONG_SIDE,
  getAspectRatioFrame,
  inferAspectRatioFromDimensions
} from './services/imageSizing';
import {
  CanvasPersistenceState,
  CanvasProject,
  createCanvasProject,
  ensureCanvasImageAsset,
  getCanvasItemAssetId,
  listCanvasProjects,
  loadCanvasProject,
  resetCanvasPersistenceSession,
  saveCanvasProject
} from './services/canvasPersistence';
import {
  AuthSession,
  clearAuthSession,
  getStoredAuthSession,
  loadCurrentUser,
  logout
} from './services/auth';
import { getApiConfig, getImageModelDisplayName, loadApiConfig, resetApiConfigSession } from './services/config';
import { type CanvasExportScope, exportCanvasProjectImage } from './services/canvasExport';
import {
  buildImageReferenceRoleContext,
  buildReferencedImageEditPrompt,
  normalizeOptimizedPromptImageReferences,
  resolveEditReferencesWithAi
} from './services/imageReferences';
import { createTransparentEditMask } from './services/imageMask';
import { buildImageResultDescription, generateImageTitleFromPrompt } from './services/imageTitle';

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 5;
const SIDEBAR_WIDTH = 384;
const LAST_PROJECT_STORAGE_KEY = 'livart_last_project_id';
const PLACEMENT_GAP = 96;
const CANVAS_TOP_SAFE_GAP = 56;
const CANVAS_BOTTOM_SAFE_GAP = 128;
const CANVAS_SIDE_SAFE_GAP = 24;
const REMOVER_PROMPT = '把圈起来的地方删除掉。';
const DERIVED_IMAGE_GAP = 20;
const AUTO_ARRANGE_IMAGE_GAP = 24;
const MAX_HISTORY_ENTRIES = 80;
const DEFAULT_CANVAS_BACKGROUND_COLOR = '#fcfcfc';

type ImageEditMode = 'local-redraw' | 'remover';

const getImageEditMaskData = (item: CanvasItem, mode: ImageEditMode) => (
  mode === 'remover'
    ? item.removerMaskData || item.maskData
    : item.redrawMaskData || item.maskData
);
type SidebarPromptSeed = {
  id: string;
  imageId: string;
  prompt?: string;
};
type CanvasHistorySnapshot = {
  items: CanvasItem[];
  pan: { x: number; y: number };
  zoom: number;
  selectedIds: string[];
  canvasBackgroundColor: string;
};

const createChatMessage = (
  text: string,
  role: 'user' | 'assistant',
  options: Pick<ChatMessage, 'imageIds' | 'imageResultCards' | 'durationMs'> = {}
): ChatMessage => ({
  id: Math.random().toString(36).substr(2, 9),
  role,
  text,
  timestamp: Date.now(),
  ...options
});

const appendImageCompletionMessage = (
  messages: ChatMessage[],
  imageId: string,
  isEdit: boolean
) => {
  const alreadyExists = messages.some(message => (
    message.role === 'assistant' &&
    (message.imageIds?.includes(imageId) || message.text.includes(`@${imageId}`))
  ));
  if (alreadyExists) return messages;

  return [
    ...messages,
    createChatMessage(
      isEdit ? `已恢复并完成图片编辑：@${imageId}` : `已恢复并完成图片生成：@${imageId}`,
      'assistant',
      {
        imageIds: [imageId],
        imageResultCards: [{
          imageId,
          modelName: getImageModelDisplayName()
        }]
      }
    )
  ];
};

const rectsOverlap = (
  left: Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>,
  right: Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>,
  padding = 36
) => {
  return left.x < right.x + right.width + padding &&
    left.x + left.width + padding > right.x &&
    left.y < right.y + right.height + padding &&
    left.y + left.height + padding > right.y;
};

const findAvailableCanvasPosition = (
  items: CanvasItem[],
  desiredX: number,
  desiredY: number,
  width: number,
  height: number,
  visibleRect?: Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>,
  collisionPadding = 36
) => {
  const imageItems = items.filter(item => item.type === 'image' && (item.status !== 'error' || !!item.content));
  const isFree = (x: number, y: number) => (
    !imageItems.some(item => rectsOverlap({ x, y, width, height }, item, collisionPadding))
  );
  const isVisible = (x: number, y: number) => {
    if (!visibleRect) return true;
    return x >= visibleRect.x &&
      y >= visibleRect.y &&
      x + width <= visibleRect.x + visibleRect.width &&
      y + height <= visibleRect.y + visibleRect.height;
  };

  if (isFree(desiredX, desiredY) && isVisible(desiredX, desiredY)) {
    return { x: desiredX, y: desiredY };
  }

  const stepX = width + PLACEMENT_GAP;
  const stepY = height + PLACEMENT_GAP;
  const candidates = Array.from({ length: 8 }, (_, radiusIndex) => {
    const radius = radiusIndex + 1;
    return [
      { x: radius * stepX, y: 0 },
      { x: 0, y: radius * stepY },
      { x: radius * stepX, y: radius * stepY },
      { x: -radius * stepX, y: 0 },
      { x: -radius * stepX, y: radius * stepY },
      { x: radius * stepX, y: -radius * stepY },
      { x: 0, y: -radius * stepY },
      { x: -radius * stepX, y: -radius * stepY }
    ];
  }).flat()
    .map(offset => ({ x: desiredX + offset.x, y: desiredY + offset.y }));

  const freeCandidate = candidates.find(candidate => isFree(candidate.x, candidate.y) && isVisible(candidate.x, candidate.y))
    || (isFree(desiredX, desiredY) ? { x: desiredX, y: desiredY } : undefined)
    || candidates.find(candidate => isFree(candidate.x, candidate.y));
  if (freeCandidate) return freeCandidate;

  const rightMost = imageItems.reduce(
    (maxRight, item) => Math.max(maxRight, item.x + item.width),
    desiredX
  );
  return { x: rightMost + PLACEMENT_GAP, y: desiredY };
};

const findNextRootImageRowPosition = (
  items: CanvasItem[],
  desiredX: number,
  desiredY: number,
  width: number,
  height: number
) => {
  const imageItems = items.filter(item => (
    item.type === 'image' &&
    (item.status !== 'error' || !!item.content)
  ));
  if (imageItems.length === 0) {
    return { x: desiredX, y: desiredY };
  }

  const imageById = new Map(imageItems.map(item => [item.id, item]));
  const rootItems = imageItems.filter(item => !item.parentId || !imageById.has(item.parentId));
  const columnX = rootItems.length > 0
    ? Math.min(...rootItems.map(item => item.x))
    : Math.min(...imageItems.map(item => item.x));
  const nextY = Math.max(...imageItems.map(item => item.y + item.height)) + AUTO_ARRANGE_IMAGE_GAP;

  return findAvailableCanvasPosition(
    items,
    columnX,
    nextY,
    width,
    height,
    undefined,
    AUTO_ARRANGE_IMAGE_GAP
  );
};

const findRightSideCanvasPosition = (
  items: CanvasItem[],
  baseItem: Pick<CanvasItem, 'id' | 'x' | 'y' | 'width' | 'height'>,
  width: number,
  height: number
) => {
  let desiredX = baseItem.x + baseItem.width + DERIVED_IMAGE_GAP;
  const desiredY = baseItem.y;
  const relevantItems = items.filter(item => (
    item.id !== baseItem.id &&
    item.type === 'image' &&
    (item.status !== 'error' || !!item.content)
  ));

  for (let attempt = 0; attempt < 40; attempt += 1) {
    const candidate = { x: desiredX, y: desiredY, width, height };
    if (!relevantItems.some(item => rectsOverlap(candidate, item, DERIVED_IMAGE_GAP / 2))) {
      return { x: desiredX, y: desiredY };
    }

    desiredX += width + DERIVED_IMAGE_GAP;
  }

  return { x: desiredX, y: desiredY };
};

const isArrangeableImageItem = (item: CanvasItem) => (
  item.type === 'image' &&
  (item.status !== 'error' || !!item.content)
);

const sortCanvasItemsTopLeft = (orderById: Map<string, number>) => (left: CanvasItem, right: CanvasItem) => {
  if (left.y !== right.y) return left.y - right.y;
  if (left.x !== right.x) return left.x - right.x;
  return (orderById.get(left.id) || 0) - (orderById.get(right.id) || 0);
};

const arrangeCanvasImageItems = (items: CanvasItem[]) => {
  const imageItems = items.filter(isArrangeableImageItem);
  if (imageItems.length <= 1) return items;

  const orderById = new Map(items.map((item, index) => [item.id, index]));
  const imageById = new Map(imageItems.map(item => [item.id, item]));
  const childrenByParentId = new Map<string, CanvasItem[]>();

  imageItems.forEach((item) => {
    if (!item.parentId || !imageById.has(item.parentId)) return;
    childrenByParentId.set(item.parentId, [
      ...(childrenByParentId.get(item.parentId) || []),
      item
    ]);
  });

  childrenByParentId.forEach((children, parentId) => {
    childrenByParentId.set(parentId, [...children].sort(sortCanvasItemsTopLeft(orderById)));
  });

  const roots = imageItems
    .filter(item => !item.parentId || !imageById.has(item.parentId))
    .sort(sortCanvasItemsTopLeft(orderById));
  const originX = Math.min(...imageItems.map(item => item.x));
  const originY = Math.min(...imageItems.map(item => item.y));
  const positionsById = new Map<string, Pick<CanvasItem, 'x' | 'y'>>();
  let nextFamilyY = originY;

  const arrangeFamily = (root: CanvasItem) => {
    if (positionsById.has(root.id)) return;

    const assignedItems = new Map<string, { lane: number; x: number }>();
    const laneHeights: number[] = [];
    let nextLane = 1;

    const assignItem = (item: CanvasItem, lane: number, x: number, lineage: Set<string>) => {
      if (assignedItems.has(item.id) || lineage.has(item.id)) return;

      assignedItems.set(item.id, { lane, x });
      laneHeights[lane] = Math.max(laneHeights[lane] || 0, item.height);

      const nextLineage = new Set(lineage);
      nextLineage.add(item.id);
      const children = childrenByParentId.get(item.id) || [];
      children.forEach((child, index) => {
        const childLane = index === 0 ? lane : nextLane++;
        assignItem(
          child,
          childLane,
          x + item.width + AUTO_ARRANGE_IMAGE_GAP,
          nextLineage
        );
      });
    };

    assignItem(root, 0, originX, new Set<string>());

    const laneY: number[] = [];
    let currentY = nextFamilyY;
    laneHeights.forEach((height, lane) => {
      laneY[lane] = currentY;
      currentY += height + AUTO_ARRANGE_IMAGE_GAP;
    });

    assignedItems.forEach(({ lane, x }, itemId) => {
      const item = imageById.get(itemId);
      if (!item) return;

      positionsById.set(itemId, {
        x: Math.round(x),
        y: Math.round((laneY[lane] || nextFamilyY) + ((laneHeights[lane] || item.height) - item.height) / 2)
      });
    });

    nextFamilyY = currentY;
  };

  roots.forEach(arrangeFamily);
  imageItems
    .sort(sortCanvasItemsTopLeft(orderById))
    .forEach(arrangeFamily);

  return items.map(item => {
    const position = positionsById.get(item.id);
    return position ? { ...item, ...position } : item;
  });
};

const cloneCanvasItems = (items: CanvasItem[]) => items.map(item => ({
  ...item,
  layers: item.layers.map(layer => ({ ...layer }))
}));

const createCanvasHistorySnapshot = (
  items: CanvasItem[],
  pan: { x: number; y: number },
  zoom: number,
  selectedIds: string[],
  canvasBackgroundColor: string
): CanvasHistorySnapshot => ({
  items: cloneCanvasItems(items),
  pan: { ...pan },
  zoom,
  selectedIds: [...selectedIds],
  canvasBackgroundColor
});

const getCanvasHistoryKey = (snapshot: CanvasHistorySnapshot) => JSON.stringify({
  items: snapshot.items,
  canvasBackgroundColor: snapshot.canvasBackgroundColor
});

const isEditableShortcutTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) return false;
  return (
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) ||
    !!target.closest('[contenteditable="true"]')
  );
};

const getVisibleCanvasRect = (
  pan: { x: number; y: number },
  zoom: number,
  showSidebar: boolean
) => {
  const viewportWidth = Math.max(320, window.innerWidth - (showSidebar ? SIDEBAR_WIDTH : 0));
  const viewportHeight = window.innerHeight;
  return {
    x: (CANVAS_SIDE_SAFE_GAP - pan.x) / zoom,
    y: (CANVAS_TOP_SAFE_GAP - pan.y) / zoom,
    width: Math.max(1, viewportWidth - CANVAS_SIDE_SAFE_GAP * 2) / zoom,
    height: Math.max(1, viewportHeight - CANVAS_TOP_SAFE_GAP - CANVAS_BOTTOM_SAFE_GAP) / zoom
  };
};

const isDataImageUrl = (value: unknown) => {
  return typeof value === 'string' && value.startsWith('data:image/');
};

const mergePersistedImageAssets = (
  currentItems: CanvasItem[],
  persistedItems: CanvasItem[]
) => {
  const persistedById = new Map(persistedItems.map(item => [item.id, item]));
  let changed = false;

  const nextItems = currentItems.map(item => {
    const persisted = persistedById.get(item.id);
    if (!persisted || item.type !== 'image') return item;

    const updates: Partial<CanvasItem> = {};
    if (isDataImageUrl(item.content) && persisted.content && persisted.content !== item.content) {
      updates.content = persisted.content;
    }
    if (persisted.assetId && persisted.assetId !== item.assetId) {
      updates.assetId = persisted.assetId;
    }
    if (persisted.previewContent && persisted.previewContent !== item.previewContent) {
      updates.previewContent = persisted.previewContent;
    }
    if (persisted.thumbnailContent && persisted.thumbnailContent !== item.thumbnailContent) {
      updates.thumbnailContent = persisted.thumbnailContent;
    }

    if (Object.keys(updates).length === 0) return item;
    changed = true;
    return { ...item, ...updates };
  });

  return changed ? nextItems : currentItems;
};

const clampZoom = (value: number) => Math.min(Math.max(value, MIN_ZOOM), MAX_ZOOM);

const createWelcomeMessage = (): ChatMessage => ({
  id: 'welcome',
  role: 'assistant',
  text: '你好！我是 livart 助手。请直接告诉我你想要生成的画面，或者右键图片添加到对话进行编辑。',
  timestamp: Date.now()
});

interface PendingCanvasSave {
  canvasId: string;
  title: string;
  state: CanvasPersistenceState;
}

interface CreateProjectModalProps {
  isOpen: boolean;
  title: string;
  isCreating: boolean;
  error: string;
  onTitleChange: (title: string) => void;
  onClose: () => void;
  onSubmit: () => void;
}

const CreateProjectModal: React.FC<CreateProjectModalProps> = ({
  isOpen,
  title,
  isCreating,
  error,
  onTitleChange,
  onClose,
  onSubmit
}) => {
  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 z-[5000000] flex items-center justify-center bg-black/40 px-4 backdrop-blur-sm">
      <div className="w-full max-w-md overflow-hidden rounded-[28px] border border-white/70 bg-white shadow-[0_40px_100px_-30px_rgba(0,0,0,0.35)]">
        <div className="flex items-start justify-between gap-4 border-b border-gray-100 px-6 py-5">
          <div>
            <div className="mb-3 flex h-11 w-11 items-center justify-center rounded-2xl bg-indigo-50 text-indigo-600">
              <FolderPlus size={20} />
            </div>
            <h2 className="text-xl font-black tracking-tight text-gray-900">新建项目</h2>
            <p className="mt-1 text-xs font-bold text-gray-400">创建一个独立画布，当前项目会继续自动保存。</p>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isCreating}
            className="rounded-xl p-2 text-gray-300 transition-all hover:bg-gray-100 hover:text-gray-700 disabled:opacity-30"
            title="关闭"
          >
            <X size={18} />
          </button>
        </div>

        <form
          className="space-y-4 px-6 py-5"
          onSubmit={(event) => {
            event.preventDefault();
            onSubmit();
          }}
        >
          <div>
            <label className="mb-2 block text-sm font-black text-gray-700">项目名称</label>
            <input
              autoFocus
              value={title}
              onChange={(event) => onTitleChange(event.target.value)}
              placeholder="例如：品牌海报方案"
              className="w-full rounded-2xl border border-gray-200 px-4 py-3 text-sm font-bold outline-none transition-all focus:border-indigo-200 focus:ring-4 focus:ring-indigo-500/10"
            />
          </div>

          {error && (
            <div className="rounded-2xl border border-red-100 bg-red-50 px-4 py-3 text-sm font-bold text-red-500">
              {error}
            </div>
          )}

          <div className="flex items-center justify-end gap-3 pt-1">
            <button
              type="button"
              onClick={onClose}
              disabled={isCreating}
              className="rounded-2xl px-4 py-3 text-sm font-black text-gray-400 transition-all hover:bg-gray-100 hover:text-gray-700 disabled:opacity-30"
            >
              取消
            </button>
            <button
              type="submit"
              disabled={isCreating}
              className="flex items-center gap-2 rounded-2xl bg-black px-5 py-3 text-sm font-black text-white shadow-lg transition-all hover:opacity-90 active:scale-[0.99] disabled:opacity-40"
            >
              {isCreating && <Loader2 size={16} className="animate-spin" />}
              创建项目
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

function App() {
  const [items, setItems] = useState<CanvasItem[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([createWelcomeMessage()]);
  const [activeTaskStartedAts, setActiveTaskStartedAts] = useState<number[]>([]);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: window.innerWidth / 4, y: window.innerHeight / 4 });
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [showSidebar, setShowSidebar] = useState(true);
  const [canvasTool, setCanvasTool] = useState<CanvasTool>('select');
  const [canvasBackgroundColor, setCanvasBackgroundColor] = useState(DEFAULT_CANVAS_BACKGROUND_COLOR);
  const [contextImage, setContextImage] = useState<CanvasItem | null>(null);
  const [sidebarPromptSeed, setSidebarPromptSeed] = useState<SidebarPromptSeed | null>(null);
  const [sidebarInputResetKey, setSidebarInputResetKey] = useState(0);
  const [selectedImageEditMode, setSelectedImageEditMode] = useState<{ imageId: string; mode: ImageEditMode } | null>(null);
  const [showConfigModal, setShowConfigModal] = useState(false);
  const [isOpeningConfigModal, setIsOpeningConfigModal] = useState(false);
  const [authSession, setAuthSession] = useState<AuthSession | null>(() => getStoredAuthSession());
  const [isAuthReady, setIsAuthReady] = useState(false);
  const [hasLoadedCanvas, setHasLoadedCanvas] = useState(false);
  const [canvasSyncStatus, setCanvasSyncStatus] = useState<'loading' | 'saving' | 'saved' | 'error'>('loading');
  const [projects, setProjects] = useState<CanvasProject[]>([]);
  const [currentProjectId, setCurrentProjectId] = useState('');
  const [currentProjectTitle, setCurrentProjectTitle] = useState('默认画布');
  const [isCreateProjectModalOpen, setIsCreateProjectModalOpen] = useState(false);
  const [newProjectTitle, setNewProjectTitle] = useState('');
  const [isCreatingProject, setIsCreatingProject] = useState(false);
  const [createProjectError, setCreateProjectError] = useState('');
  const [isExportingProjectImage, setIsExportingProjectImage] = useState(false);
  const [isExportMenuOpen, setIsExportMenuOpen] = useState(false);
  const [exportProjectImageError, setExportProjectImageError] = useState('');
  const saveTimerRef = useRef<number | null>(null);
  const pendingSaveRef = useRef<PendingCanvasSave | null>(null);
  const isSavingCanvasRef = useRef(false);
  const saveRevisionRef = useRef(Date.now());
  const resumedImageJobIdsRef = useRef<Set<string>>(new Set());
  const canvasHistoryPastRef = useRef<CanvasHistorySnapshot[]>([]);
  const canvasHistoryFutureRef = useRef<CanvasHistorySnapshot[]>([]);
  const canvasHistoryTimerRef = useRef<number | null>(null);
  const isRestoringCanvasHistoryRef = useRef(false);
  const [canvasHistoryState, setCanvasHistoryState] = useState({ canUndo: false, canRedo: false });
  const hasPendingImageJob = items.some(item => item.type === 'image' && item.status === 'loading' && !!item.imageJobId);
  const hasDownloadableImage = items.some(item => item.type === 'image' && item.status === 'completed' && !!item.content);
  const hasSelectedDownloadableImage = selectedIds.some(id => {
    const item = items.find(candidate => candidate.id === id);
    return item?.type === 'image' && item.status === 'completed' && !!item.content;
  });

  const updateCanvasHistoryState = () => {
    setCanvasHistoryState({
      canUndo: canvasHistoryPastRef.current.length > 1,
      canRedo: canvasHistoryFutureRef.current.length > 0
    });
  };

  const getCurrentCanvasHistorySnapshot = () => createCanvasHistorySnapshot(items, pan, zoom, selectedIds, canvasBackgroundColor);

  const resetCanvasHistory = (snapshot: CanvasHistorySnapshot) => {
    if (canvasHistoryTimerRef.current) {
      window.clearTimeout(canvasHistoryTimerRef.current);
      canvasHistoryTimerRef.current = null;
    }
    canvasHistoryPastRef.current = [snapshot];
    canvasHistoryFutureRef.current = [];
    updateCanvasHistoryState();
  };

  const commitCanvasHistory = () => {
    canvasHistoryTimerRef.current = null;
    const snapshot = getCurrentCanvasHistorySnapshot();
    const lastSnapshot = canvasHistoryPastRef.current.at(-1);

    if (lastSnapshot && getCanvasHistoryKey(lastSnapshot) === getCanvasHistoryKey(snapshot)) {
      updateCanvasHistoryState();
      return;
    }

    canvasHistoryPastRef.current = [
      ...canvasHistoryPastRef.current,
      snapshot
    ].slice(-MAX_HISTORY_ENTRIES);
    canvasHistoryFutureRef.current = [];
    updateCanvasHistoryState();
  };

  const flushCanvasHistory = () => {
    if (canvasHistoryTimerRef.current) {
      window.clearTimeout(canvasHistoryTimerRef.current);
      canvasHistoryTimerRef.current = null;
    }
    commitCanvasHistory();
  };

  const restoreCanvasHistorySnapshot = (snapshot: CanvasHistorySnapshot) => {
    isRestoringCanvasHistoryRef.current = true;
    setItems(cloneCanvasItems(snapshot.items));
    setSelectedIds([...snapshot.selectedIds]);
    setCanvasBackgroundColor(snapshot.canvasBackgroundColor || DEFAULT_CANVAS_BACKGROUND_COLOR);
    setContextImage(null);
    setSidebarPromptSeed(null);
    setSelectedImageEditMode(null);
    window.setTimeout(() => {
      isRestoringCanvasHistoryRef.current = false;
    }, 0);
  };

  const undoCanvas = () => {
    if (canvasHistoryTimerRef.current) {
      window.clearTimeout(canvasHistoryTimerRef.current);
      canvasHistoryTimerRef.current = null;
    }

    const currentSnapshot = getCurrentCanvasHistorySnapshot();
    const lastSnapshot = canvasHistoryPastRef.current.at(-1);
    if (!lastSnapshot) return;

    const currentKey = getCanvasHistoryKey(currentSnapshot);
    const lastKey = getCanvasHistoryKey(lastSnapshot);
    let targetSnapshot: CanvasHistorySnapshot | undefined;

    if (currentKey !== lastKey) {
      canvasHistoryFutureRef.current = [currentSnapshot, ...canvasHistoryFutureRef.current];
      targetSnapshot = lastSnapshot;
    } else if (canvasHistoryPastRef.current.length > 1) {
      const currentCommittedSnapshot = canvasHistoryPastRef.current.pop();
      if (currentCommittedSnapshot) {
        canvasHistoryFutureRef.current = [currentCommittedSnapshot, ...canvasHistoryFutureRef.current];
      }
      targetSnapshot = canvasHistoryPastRef.current.at(-1);
    }

    if (!targetSnapshot) {
      updateCanvasHistoryState();
      return;
    }

    restoreCanvasHistorySnapshot(targetSnapshot);
    updateCanvasHistoryState();
  };

  const redoCanvas = () => {
    if (canvasHistoryTimerRef.current) {
      window.clearTimeout(canvasHistoryTimerRef.current);
      canvasHistoryTimerRef.current = null;
    }

    const nextSnapshot = canvasHistoryFutureRef.current[0];
    if (!nextSnapshot) {
      updateCanvasHistoryState();
      return;
    }

    canvasHistoryFutureRef.current = canvasHistoryFutureRef.current.slice(1);
    canvasHistoryPastRef.current = [
      ...canvasHistoryPastRef.current,
      nextSnapshot
    ].slice(-MAX_HISTORY_ENTRIES);
    restoreCanvasHistorySnapshot(nextSnapshot);
    updateCanvasHistoryState();
  };

  const resetWorkspace = () => {
    setItems([]);
    setMessages([createWelcomeMessage()]);
    setZoom(1);
    setPan({ x: window.innerWidth / 4, y: window.innerHeight / 4 });
    setCanvasBackgroundColor(DEFAULT_CANVAS_BACKGROUND_COLOR);
    setSelectedIds([]);
    setContextImage(null);
    setSidebarPromptSeed(null);
    setSidebarInputResetKey(prev => prev + 1);
    setSelectedImageEditMode(null);
    resetCanvasHistory(createCanvasHistorySnapshot([], { x: window.innerWidth / 4, y: window.innerHeight / 4 }, 1, [], DEFAULT_CANVAS_BACKGROUND_COLOR));
    setProjects([]);
    setCurrentProjectId('');
    setCurrentProjectTitle('默认画布');
    if (saveTimerRef.current) {
      window.clearTimeout(saveTimerRef.current);
      saveTimerRef.current = null;
    }
    pendingSaveRef.current = null;
    isSavingCanvasRef.current = false;
    resetCanvasPersistenceSession();
  };

  const flushQueuedCanvasSave = () => {
    if (isSavingCanvasRef.current || !pendingSaveRef.current) return;

    const savePayload = pendingSaveRef.current;
    pendingSaveRef.current = null;
    isSavingCanvasRef.current = true;
    saveRevisionRef.current += 1;
    setCanvasSyncStatus('saving');

    saveCanvasProject(savePayload.canvasId, savePayload.state, saveRevisionRef.current, savePayload.title)
      .then((persistentState) => {
        if (!pendingSaveRef.current) {
          setItems(prev => mergePersistedImageAssets(prev, persistentState.items));
          setCanvasSyncStatus('saved');
        }
      })
      .catch((error) => {
        console.warn('[canvas-persistence] save failed', error);
        setCanvasSyncStatus('error');
      })
      .finally(() => {
        isSavingCanvasRef.current = false;
        if (pendingSaveRef.current) {
          flushQueuedCanvasSave();
        }
      });
  };

  const applyLoadedProject = (project: CanvasProject, state: CanvasPersistenceState) => {
    setCurrentProjectId(project.id);
    setCurrentProjectTitle(project.title || '未命名项目');
    localStorage.setItem(LAST_PROJECT_STORAGE_KEY, project.id);
    saveRevisionRef.current = Math.max(Date.now(), project.revision || 0);
    setItems(state.items);
    setMessages(state.messages.length > 0 ? state.messages : [createWelcomeMessage()]);
    setZoom(state.viewport.zoom);
    setPan(state.viewport.pan);
    setCanvasBackgroundColor(state.settings.backgroundColor || DEFAULT_CANVAS_BACKGROUND_COLOR);
    setSelectedIds([]);
    setContextImage(null);
    setSidebarPromptSeed(null);
    setSidebarInputResetKey(prev => prev + 1);
    setSelectedImageEditMode(null);
    resetCanvasHistory(createCanvasHistorySnapshot(state.items, state.viewport.pan, state.viewport.zoom, [], state.settings.backgroundColor || DEFAULT_CANVAS_BACKGROUND_COLOR));
    resumedImageJobIdsRef.current = new Set();
  };

  const loadProjectById = async (projectId: string) => {
    setHasLoadedCanvas(false);
    setCanvasSyncStatus('loading');
    const loaded = await loadCanvasProject(projectId);
    applyLoadedProject(loaded.project, loaded.state);
    setProjects(prev => {
      const exists = prev.some(project => project.id === loaded.project.id);
      if (!exists) return [loaded.project, ...prev];
      return prev.map(project => project.id === loaded.project.id ? loaded.project : project);
    });
    setCanvasSyncStatus('saved');
    setHasLoadedCanvas(true);
  };

  useEffect(() => {
    let isMounted = true;

    loadCurrentUser()
      .then((session) => {
        if (!isMounted) return;
        if (session) {
          setAuthSession(session);
        } else {
          clearAuthSession();
          setAuthSession(null);
        }
      })
      .catch((error) => {
        console.warn('[auth] restore session failed', error);
        clearAuthSession();
        if (isMounted) {
          setAuthSession(null);
        }
      })
      .finally(() => {
        if (isMounted) {
          setIsAuthReady(true);
        }
      });

    return () => {
      isMounted = false;
    };
  }, []);

  useEffect(() => {
    if (!isAuthReady || authSession) return;
    resetApiConfigSession();
    setShowConfigModal(false);
  }, [isAuthReady, authSession]);

  useEffect(() => {
    if (!isAuthReady) return;

    let isMounted = true;

    if (!authSession) {
      resetWorkspace();
      setHasLoadedCanvas(false);
      setCanvasSyncStatus('loading');
      return () => {
        isMounted = false;
      };
    }

    setHasLoadedCanvas(false);
    setCanvasSyncStatus('loading');

    (async () => {
      const projectList = await listCanvasProjects();
      const initialProject = projectList.length > 0
        ? projectList.find(project => project.id === localStorage.getItem(LAST_PROJECT_STORAGE_KEY)) || projectList[0]
        : (await createCanvasProject('默认画布')).project;

      const normalizedProjects = projectList.length > 0 ? projectList : [initialProject];
      const loaded = await loadCanvasProject(initialProject.id);

      return { normalizedProjects, loaded };
    })()
      .then(({ normalizedProjects, loaded }) => {
        if (!isMounted) return;
        setProjects(normalizedProjects);
        applyLoadedProject(loaded.project, loaded.state);
        setCanvasSyncStatus('saved');
      })
      .catch((error) => {
        console.warn('[canvas-persistence] load failed', error);
        if (isMounted) {
          setCanvasSyncStatus('error');
        }
      })
      .finally(() => {
        if (isMounted) {
          setHasLoadedCanvas(true);
        }
      });

    return () => {
      isMounted = false;
    };
  }, [isAuthReady, authSession?.token]);

  useEffect(() => {
    if (!authSession || !hasLoadedCanvas || !currentProjectId) return;
    if (saveTimerRef.current) {
      window.clearTimeout(saveTimerRef.current);
    }

    pendingSaveRef.current = {
      canvasId: currentProjectId,
      title: currentProjectTitle,
      state: {
        items,
        messages,
        viewport: { zoom, pan },
        settings: { backgroundColor: canvasBackgroundColor },
        selectedIds: []
      }
    };

    saveTimerRef.current = window.setTimeout(flushQueuedCanvasSave, hasPendingImageJob ? 50 : 800);

    return () => {
      if (saveTimerRef.current) {
        window.clearTimeout(saveTimerRef.current);
      }
    };
  }, [items, messages, zoom, pan, canvasBackgroundColor, hasLoadedCanvas, currentProjectId, currentProjectTitle, authSession]);

  useEffect(() => {
    if (!authSession || !hasLoadedCanvas || !currentProjectId) return;
    if (isRestoringCanvasHistoryRef.current) return;

    if (canvasHistoryPastRef.current.length === 0) {
      resetCanvasHistory(getCurrentCanvasHistorySnapshot());
      return;
    }

    if (canvasHistoryTimerRef.current) {
      window.clearTimeout(canvasHistoryTimerRef.current);
    }

    canvasHistoryTimerRef.current = window.setTimeout(commitCanvasHistory, 350);

    return () => {
      if (canvasHistoryTimerRef.current) {
        window.clearTimeout(canvasHistoryTimerRef.current);
        canvasHistoryTimerRef.current = null;
      }
    };
  }, [items, canvasBackgroundColor, hasLoadedCanvas, currentProjectId, authSession]);

  useEffect(() => {
    const handleHistoryShortcut = (event: KeyboardEvent) => {
      if (isEditableShortcutTarget(event.target)) return;

      const key = event.key.toLowerCase();
      const isModifierPressed = event.metaKey || event.ctrlKey;
      const hasShortcutModifier = isModifierPressed || event.altKey;

      if (!hasShortcutModifier && key === 'h') {
        event.preventDefault();
        setCanvasTool('pan');
        return;
      }

      if (!hasShortcutModifier && key === 'v') {
        event.preventDefault();
        setCanvasTool('select');
        return;
      }

      if (!isModifierPressed) return;

      if (key === 'z' && event.shiftKey) {
        event.preventDefault();
        redoCanvas();
      } else if (key === 'z') {
        event.preventDefault();
        undoCanvas();
      } else if (key === 'y') {
        event.preventDefault();
        redoCanvas();
      }
    };

    window.addEventListener('keydown', handleHistoryShortcut);
    return () => window.removeEventListener('keydown', handleHistoryShortcut);
  });

  useEffect(() => {
    if (!hasLoadedCanvas || !currentProjectId) return;

    const pendingJobItems = items.filter(item => (
      item.type === 'image' &&
      item.status === 'loading' &&
      !!item.imageJobId
    ));

    pendingJobItems.forEach((item) => {
      const jobId = item.imageJobId;
      if (!jobId || resumedImageJobIdsRef.current.has(jobId)) return;
      resumedImageJobIdsRef.current.add(jobId);

      waitForImageJob(jobId)
        .then(async (imageResult) => {
          const resultImg = imageResult.image;
          const persistedImageItem = await ensureCanvasImageAsset({
            ...item,
            content: resultImg,
            status: 'completed'
          });

          setItems(prev => prev.map(candidate => {
            if (candidate.id !== item.id) return candidate;
            const parentImage = candidate.parentId
              ? prev.find(prevItem => prevItem.id === candidate.parentId && prevItem.type === 'image') || null
              : null;
            const rawOptimizedPrompt = imageResult.optimizedPrompt || candidate.optimizedPrompt;
            const optimizedPrompt = parentImage && rawOptimizedPrompt
              ? normalizeOptimizedPromptImageReferences(rawOptimizedPrompt, parentImage, [], prev)
              : rawOptimizedPrompt;
            return {
              ...candidate,
              content: persistedImageItem.content,
              assetId: persistedImageItem.assetId,
              previewContent: persistedImageItem.previewContent,
              thumbnailContent: persistedImageItem.thumbnailContent,
              status: 'completed',
              imageJobId: undefined,
              originalPrompt: candidate.originalPrompt || imageResult.originalPrompt,
              optimizedPrompt,
              label: generateImageTitleFromPrompt(
                candidate.originalPrompt || imageResult.originalPrompt || candidate.prompt || optimizedPrompt || '',
                candidate.label || '生成图片'
              )
            };
          }));
          setMessages(prev => appendImageCompletionMessage(prev, item.id, !!item.parentId));
        })
        .catch((error) => {
          const message = error instanceof Error ? error.message : '图片任务恢复失败';
          setItems(prev => prev.filter(candidate => candidate.id !== item.id));
          setSelectedIds(prev => prev.filter(id => id !== item.id));
          setContextImage(prev => prev?.id === item.id ? null : prev);
          setMessages(prev => [
            ...prev,
            createChatMessage(`恢复中的图片任务失败：${message}`, 'assistant')
          ]);
        });
    });
  }, [hasLoadedCanvas, currentProjectId]);

  const canvasSyncText = canvasSyncStatus === 'loading'
    ? '永久画布读取中'
    : canvasSyncStatus === 'saving'
      ? '永久画布保存中'
      : canvasSyncStatus === 'saved'
        ? '永久画布已同步'
        : '永久画布未连接';

  const handleProjectChange = (projectId: string) => {
    if (!projectId || projectId === currentProjectId) return;
    flushQueuedCanvasSave();
    loadProjectById(projectId).catch(error => {
      console.warn('[canvas-persistence] switch project failed', error);
      setCanvasSyncStatus('error');
      setHasLoadedCanvas(true);
    });
  };

  const openCreateProjectModal = () => {
    setNewProjectTitle(`新项目 ${projects.length + 1}`);
    setCreateProjectError('');
    setIsCreateProjectModalOpen(true);
  };

  const handleCreateProject = async () => {
    if (isCreatingProject) return;

    const fallbackTitle = `新项目 ${projects.length + 1}`;
    const title = newProjectTitle.trim() || fallbackTitle;

    flushQueuedCanvasSave();
    setIsCreatingProject(true);
    setCreateProjectError('');
    setHasLoadedCanvas(false);
    setCanvasSyncStatus('loading');

    try {
      const created = await createCanvasProject(title);
      setProjects(prev => [created.project, ...prev]);
      applyLoadedProject(created.project, created.state);
      setIsCreateProjectModalOpen(false);
      setNewProjectTitle('');
      setCanvasSyncStatus('saved');
    } catch (error) {
      console.warn('[canvas-persistence] create project failed', error);
      setCreateProjectError(error instanceof Error ? error.message : '创建项目失败，请稍后重试');
      setCanvasSyncStatus('error');
    } finally {
      setIsCreatingProject(false);
      setHasLoadedCanvas(true);
    }
  };

  const getCanvasCenterPoint = () => {
    const availableWidth = window.innerWidth - (showSidebar ? SIDEBAR_WIDTH : 0);
    return {
      x: availableWidth / 2,
      y: window.innerHeight / 2
    };
  };

  const getPanForCanvasFrame = (frame: Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>) => {
    const availableWidth = window.innerWidth - (showSidebar ? SIDEBAR_WIDTH : 0);
    const targetCenterX = frame.x + frame.width / 2;
    const targetCenterY = frame.y + frame.height / 2;

    return {
      x: availableWidth / 2 - targetCenterX * zoom,
      y: window.innerHeight / 2 - targetCenterY * zoom
    };
  };

  const handleZoomChange = (nextZoom: number, anchorPoint = getCanvasCenterPoint()) => {
    const clampedZoom = clampZoom(nextZoom);
    const worldX = (anchorPoint.x - pan.x) / zoom;
    const worldY = (anchorPoint.y - pan.y) / zoom;

    setPan({
      x: anchorPoint.x - worldX * clampedZoom,
      y: anchorPoint.y - worldY * clampedZoom
    });
    setZoom(clampedZoom);
  };

  const addWorkflow = () => {
    const newId = Math.random().toString(36).substr(2, 9);
    const newItem: CanvasItem = {
      id: newId,
      type: 'workflow',
      content: '',
      x: (-pan.x + window.innerWidth/2) / zoom - 250,
      y: (-pan.y + window.innerHeight/2) / zoom - 200,
      width: 500,
      height: 400,
      status: 'completed',
      zIndex: 100,
      layers: []
    };
    setItems(prev => [...prev, newItem]);
    setSelectedIds([newId]);
  };

  const addImageItem = (file: File, dropX?: number, dropY?: number) => {
    const reader = new FileReader();
    reader.onload = (e) => {
      const content = e.target?.result as string;
      const img = new Image();
      img.onload = () => {
        const maxInitialSize = 400;
        let width = img.naturalWidth;
        let height = img.naturalHeight;

        if (width > maxInitialSize || height > maxInitialSize) {
          const ratio = width / height;
          if (width > height) {
            width = maxInitialSize;
            height = maxInitialSize / ratio;
          } else {
            height = maxInitialSize;
            width = maxInitialSize * ratio;
          }
        }

        const newId = Math.random().toString(36).substr(2, 9);
        const newItem: CanvasItem = {
          id: newId,
          type: 'image',
          content: '',
          x: (dropX !== undefined ? dropX : (-pan.x + window.innerWidth/2) / zoom) - width / 2,
          y: (dropY !== undefined ? dropY : (-pan.y + window.innerHeight/2) / zoom) - height / 2,
          width: width,
          height: height,
          status: 'loading',
          label: `${file.name} 上传中...`,
          zIndex: 50,
          layers: []
        };
        setItems(prev => [...prev, newItem]);
        setSelectedIds([newId]);
        ensureCanvasImageAsset({
          ...newItem,
          content,
          status: 'completed',
          label: file.name
        })
          .then((persistedItem) => {
            const completedItem = {
              ...newItem,
              ...persistedItem,
              status: 'completed' as const,
              label: file.name
            };
            setItems(prev => prev.map(item => item.id === newId ? completedItem : item));
            focusImageInSidebarInput(completedItem);
          })
          .catch((error) => {
            const message = error instanceof Error ? error.message : '图片上传失败';
            setItems(prev => prev.map(item => (
              item.id === newId
                ? { ...item, status: 'error', label: message }
                : item
            )));
          });
      };
      img.src = content;
    };
    reader.readAsDataURL(file);
  };

  const addTextItem = (x?: number, y?: number) => {
    const newId = Math.random().toString(36).substr(2, 9);
    const width = 2;
    const height = 40;
    const nextZIndex = Math.max(0, ...items.map(item => item.zIndex || 0)) + 1;
    const newItem: CanvasItem = {
      id: newId,
      type: 'text',
      content: '',
      x: x !== undefined ? x : ((-pan.x + window.innerWidth/2) / zoom - width / 2),
      y: y !== undefined ? y : ((-pan.y + window.innerHeight/2) / zoom - height / 2),
      width: width,
      height: height,
      status: 'completed',
      label: '文字图层',
      zIndex: nextZIndex,
      textStyle: {
        fontFamily: 'Inter',
        fontSize: 32,
        fontWeight: 700,
        fontStyle: 'normal',
        textDecoration: 'none',
        color: '#18181b',
        strokeColor: '#ffffff',
        strokeWidth: 0,
        backgroundColor: 'transparent',
        textAlign: 'left',
        lineHeight: 1.2
      },
      layers: []
    };
    setItems(prev => [...prev, newItem]);
    setSelectedIds([newId]);
    return newId;
  };

  const addMessage = (text: string, role: 'user' | 'assistant', options: Pick<ChatMessage, 'imageIds' | 'imageResultCards' | 'durationMs'> = {}) => {
    setMessages(prev => [...prev, createChatMessage(text, role, options)]);
  };

  const startTaskTimer = (startedAt: number) => {
    setActiveTaskStartedAts(prev => [...prev, startedAt]);
  };

  const finishTaskTimer = (startedAt: number) => {
    setActiveTaskStartedAts(prev => {
      const startedAtIndex = prev.indexOf(startedAt);
      if (startedAtIndex < 0) return prev.slice(1);
      return prev.filter((_, index) => index !== startedAtIndex);
    });
  };

  const finishOldestTaskTimer = () => {
    setActiveTaskStartedAts(prev => prev.slice(1));
  };

  const handleCanvasChatMessage = (
    text: string,
    role: 'user' | 'assistant',
    options: Pick<ChatMessage, 'imageIds' | 'imageResultCards' | 'durationMs'> = {}
  ) => {
    setShowSidebar(true);
    if (role === 'user') {
      startTaskTimer(Date.now());
    } else if (typeof options.durationMs === 'number') {
      finishOldestTaskTimer();
    }
    addMessage(text, role, options);
  };

  const handleUpdateItem = (id: string, updates: Partial<CanvasItem>) => {
    const syncContextImage = (nextUpdates: Partial<CanvasItem>) => {
      setContextImage(prev => prev?.id === id ? { ...prev, ...nextUpdates } : prev);
    };

    if (isDataImageUrl(updates.content)) {
      let uploadCandidate: CanvasItem | null = null;
      setItems(prev => prev.map(item => {
        if (item.id !== id) return item;
        const nextItem = { ...item, ...updates };
        if (nextItem.type === 'image' && nextItem.status === 'completed') {
          uploadCandidate = nextItem;
          return {
            ...nextItem,
            content: '',
            status: 'loading',
            label: `${nextItem.label || '图片'} 上传中...`
          };
        }
        return nextItem;
      }));

      if (uploadCandidate) {
        const imageToUpload = uploadCandidate;
        ensureCanvasImageAsset(imageToUpload)
          .then((persistedItem) => {
            const completedItem = {
              ...imageToUpload,
              ...persistedItem,
              status: 'completed' as const
            };
            setItems(prev => prev.map(item => item.id === id ? completedItem : item));
            syncContextImage(completedItem);
          })
          .catch((error) => {
            const message = error instanceof Error ? error.message : '图片上传失败';
            const errorUpdates = { status: 'error' as const, label: message };
            setItems(prev => prev.map(item => (
              item.id === id
                ? { ...item, ...errorUpdates }
                : item
            )));
            syncContextImage(errorUpdates);
          });
        return;
      }
      syncContextImage(updates);
      return;
    }

    setItems(prev => prev.map(item => item.id === id ? { ...item, ...updates } : item));
    syncContextImage(updates);
  };

  const handleDeleteItems = (ids: string[]) => {
    setItems(prev => prev.filter(i => !ids.includes(i.id)));
    setSelectedIds([]);
    if (contextImage && ids.includes(contextImage.id)) {
      setContextImage(null);
    }
    setSelectedImageEditMode(prev => prev && ids.includes(prev.imageId) ? null : prev);
  };

  const focusImageInSidebarInput = (item: CanvasItem, prompt?: string, mode?: ImageEditMode) => {
    if (item.type !== 'image' || item.status !== 'completed' || !item.content) return;

    setContextImage(item);
    setShowSidebar(true);
    setSidebarPromptSeed({
      id: `${item.id}-${Date.now()}-${Math.random().toString(36).slice(2)}`,
      imageId: item.id,
      prompt
    });
    setSelectedImageEditMode(mode ? { imageId: item.id, mode } : null);
  };

  const handleCanvasSelectionChange = (ids: string[]) => {
    setSelectedIds(ids);

    setSelectedImageEditMode(prev => (
      prev && ids.length === 1 && ids[0] === prev.imageId ? prev : null
    ));

    if (ids.length !== 1) return;

    const selectedImage = items.find(item => (
      item.id === ids[0] &&
      item.type === 'image' &&
      item.status === 'completed' &&
      !!item.content
    ));
    if (selectedImage) {
      focusImageInSidebarInput(selectedImage);
    }
  };

  const handleClearSidebarContextImage = () => {
    setContextImage(null);
    setSelectedImageEditMode(null);
  };

  const handleAutoArrangeImages = () => {
    if (items.filter(isArrangeableImageItem).length <= 1) return;

    flushCanvasHistory();
    setItems(prev => arrangeCanvasImageItems(prev));
  };

  const handleSidebarSendMessage = async (text: string, aspectRatio: ImageAspectRatio = 'auto') => {
    const startedAt = Date.now();
    addMessage(text, 'user');
    startTaskTimer(startedAt);

    let newId = '';
    let editBaseImage: CanvasItem | null = null;

    try {
      const freshContextImage = contextImage
        ? items.find(item => item.id === contextImage.id && item.type === 'image') || contextImage
        : null;
      const editReferences = (await resolveEditReferencesWithAi(text, freshContextImage, items))
        .map(reference => items.find(item => item.id === reference.id && item.type === 'image') || reference);
      editBaseImage = editReferences[0] || null;
      const editReferenceImages = editReferences.slice(1);
      const effectiveAspectRatio = editBaseImage && aspectRatio === 'auto'
        ? inferAspectRatioFromDimensions(editBaseImage.width, editBaseImage.height)
        : aspectRatio;
      const activeImageEditMode = editBaseImage && selectedImageEditMode?.imageId === editBaseImage.id
        ? selectedImageEditMode.mode
        : null;

      newId = Math.random().toString(36).substr(2, 9);
      const fallbackWidth = editBaseImage ? editBaseImage.width : DEFAULT_GENERATED_IMAGE_LONG_SIDE;
      const fallbackHeight = editBaseImage ? editBaseImage.height : DEFAULT_GENERATED_IMAGE_LONG_SIDE;
      const maxLongSide = editBaseImage
        ? Math.max(editBaseImage.width, editBaseImage.height)
        : DEFAULT_GENERATED_IMAGE_LONG_SIDE;
      const initialFrame = getAspectRatioFrame(effectiveAspectRatio, fallbackWidth, fallbackHeight, maxLongSide);
      const visibleCanvasRect = getVisibleCanvasRect(pan, zoom, showSidebar);
      const canvasCenterX = visibleCanvasRect.x + visibleCanvasRect.width / 2;
      const canvasCenterY = visibleCanvasRect.y + visibleCanvasRect.height / 2;
      const generatedPosition = editBaseImage
        ? findRightSideCanvasPosition(items, editBaseImage, initialFrame.width, initialFrame.height)
        : findNextRootImageRowPosition(
          items,
          canvasCenterX - initialFrame.width / 2,
          canvasCenterY - initialFrame.height / 2,
          initialFrame.width,
          initialFrame.height
        );

      const newItem: CanvasItem = {
        id: newId,
        type: 'image',
        content: '',
        x: generatedPosition.x,
        y: generatedPosition.y,
        width: initialFrame.width,
        height: initialFrame.height,
        status: 'loading',
        label: editBaseImage ? 'AI 编辑中...' : 'AI 生成中...',
        zIndex: Math.max(60, ...items.map(item => item.zIndex || 0)) + 1,
        parentId: editBaseImage?.id,
        prompt: text,
        originalPrompt: text,
        layers: []
      };

      setItems(prev => [...prev, newItem]);

      let imageResult: ImageGenerationResult;
      let requestPrompt = text;
      let latestOptimizedPrompt = '';
      let optimizedPromptBaseImage = editBaseImage;
      let optimizedPromptReferenceImages = editReferenceImages;
      if (editBaseImage) {
        const persistedEditImages = await Promise.all(
          [editBaseImage, ...editReferenceImages].map(item => ensureCanvasImageAsset(item))
        );
        const persistedBaseImage = persistedEditImages[0];
        const persistedReferenceImages = persistedEditImages.slice(1);
        optimizedPromptBaseImage = persistedBaseImage;
        optimizedPromptReferenceImages = persistedReferenceImages;
        const persistedById = new Map(persistedEditImages.map(item => [item.id, item]));
        setItems(prev => prev.map(item => persistedById.get(item.id) || item));

        const localMaskData = activeImageEditMode ? getImageEditMaskData(editBaseImage, activeImageEditMode) : undefined;
        if (activeImageEditMode && !localMaskData) {
          throw new Error(activeImageEditMode === 'remover' ? '请先用画笔涂抹需要删除的物体' : '请先用画笔涂抹需要局部重绘的区域');
        }

        const promptToOptimize = activeImageEditMode
          ? activeImageEditMode === 'remover'
            ? `${text}。只删除或修复用户用蒙版涂抹的局部区域，未被蒙版覆盖的区域必须保持原图不变。`
            : `${text}。只修改用户用蒙版涂抹的局部区域，未被蒙版覆盖的区域必须保持原图不变。`
          : text;
        const editPrompt = buildReferencedImageEditPrompt(promptToOptimize, persistedBaseImage, persistedReferenceImages, {
          hasLocalMask: !!activeImageEditMode,
          allItems: items
        });
        requestPrompt = editPrompt;
        const referenceContents = persistedReferenceImages.map(item => item.content);
        const imageContext = buildImageReferenceRoleContext(promptToOptimize, persistedBaseImage, persistedReferenceImages, {
          hasLocalMask: !!activeImageEditMode,
          allItems: items
        });
        const requestImageContext = activeImageEditMode === 'remover'
          ? [
            '任务类型：image-remover / object removal / inpainting。',
            'mask 透明区域就是用户圈选/涂抹的删除区域，必须删除该区域内的内容并自然补全背景。',
            '未被 mask 覆盖的区域必须尽量保持原图不变。',
            imageContext
          ].join('\n')
          : imageContext;
        const maskDataUrl = activeImageEditMode
          ? await createTransparentEditMask(
            localMaskData!,
            persistedBaseImage.content,
            editBaseImage.width,
            editBaseImage.height,
            activeImageEditMode === 'remover'
              ? { outlineDilationRadius: 6, editableDilationRadius: 5 }
              : undefined
          )
          : undefined;
        if (activeImageEditMode && !maskDataUrl) {
          throw new Error(activeImageEditMode === 'remover' ? '请先用画笔涂抹需要删除的物体' : '请先用画笔涂抹需要局部重绘的区域');
        }
        const editOptions = {
          imageAssetId: getCanvasItemAssetId(persistedBaseImage),
          referenceAssetIds: persistedReferenceImages.map(getCanvasItemAssetId).filter(Boolean),
          imageContext: requestImageContext,
          promptOptimizationMode: activeImageEditMode === 'remover' ? 'image-remover' as const : undefined
        };
        const requestAspectRatio = activeImageEditMode === 'remover' ? 'auto' : effectiveAspectRatio;
        // 执行图像编辑
        if (canUseImageJobs()) {
          const job = await submitImageEditJob(
            editPrompt,
            persistedBaseImage.content,
            maskDataUrl || undefined,
            requestAspectRatio,
            referenceContents,
            editOptions
          );
          latestOptimizedPrompt = job.optimizedPrompt || '';
          const storedOptimizedPrompt = job.optimizedPrompt
            ? normalizeOptimizedPromptImageReferences(job.optimizedPrompt, persistedBaseImage, persistedReferenceImages, items)
            : '';
          setItems(prev => prev.map(i => i.id === newId ? {
            ...i,
            prompt: editPrompt,
            imageJobId: job.jobId,
            originalPrompt: text,
            optimizedPrompt: storedOptimizedPrompt || i.optimizedPrompt
          } : i));
          imageResult = await waitForImageJob(job.jobId);
        } else {
          imageResult = await editImage(
            editPrompt,
            persistedBaseImage.content,
            maskDataUrl || undefined,
            requestAspectRatio,
            referenceContents,
            editOptions
          );
        }
      } else {
        // 直接文本生成图片
        if (canUseImageJobs()) {
          const job = await submitImageGenerationJob(text, aspectRatio);
          latestOptimizedPrompt = job.optimizedPrompt || '';
          setItems(prev => prev.map(i => i.id === newId ? {
            ...i,
            imageJobId: job.jobId,
            originalPrompt: text,
            optimizedPrompt: job.optimizedPrompt || i.optimizedPrompt
          } : i));
          imageResult = await waitForImageJob(job.jobId);
        } else {
          imageResult = await generateImage(text, 'none', aspectRatio);
        }
      }

      const resultImg = imageResult.image;
      const persistedImageItem = await ensureCanvasImageAsset({
        ...newItem,
        content: resultImg,
        status: 'completed'
      });
      const resultTitle = generateImageTitleFromPrompt(
        text || requestPrompt || imageResult.optimizedPrompt || latestOptimizedPrompt || '',
        editBaseImage ? '编辑结果' : '生成图片'
      );
      const resultDescription = buildImageResultDescription(resultTitle, editBaseImage ? 'edited' : 'generated');

      setItems(prev => prev.map(i => {
        if (i.id !== newId) return i;
        const rawOptimizedPrompt = imageResult.optimizedPrompt || latestOptimizedPrompt || imageResult.originalPrompt || requestPrompt;
        const optimizedPrompt = optimizedPromptBaseImage && rawOptimizedPrompt
          ? normalizeOptimizedPromptImageReferences(rawOptimizedPrompt, optimizedPromptBaseImage, optimizedPromptReferenceImages, prev)
          : rawOptimizedPrompt;
        return {
          ...i,
          content: persistedImageItem.content,
          assetId: persistedImageItem.assetId,
          previewContent: persistedImageItem.previewContent,
          thumbnailContent: persistedImageItem.thumbnailContent,
          status: 'completed',
          imageJobId: undefined,
          prompt: requestPrompt,
          originalPrompt: text,
          optimizedPrompt,
          label: resultTitle
        };
      }));

      addMessage(resultDescription, 'assistant', {
        imageIds: [newId],
        imageResultCards: [{
          imageId: newId,
          modelName: getImageModelDisplayName(getApiConfig().model),
          title: resultTitle,
          description: resultDescription
        }],
        durationMs: Date.now() - startedAt
      });

      setContextImage(null);
      setSelectedImageEditMode(null);
      setSidebarPromptSeed(null);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      if (newId) {
        setItems(prev => prev.filter(item => item.id !== newId));
      }
      setSelectedIds(editBaseImage ? [editBaseImage.id] : []);
      setContextImage(prev => prev?.id === newId ? null : prev);
      addMessage(`出错了，没能完成生成：${errorMessage}`, 'assistant', {
        durationMs: Date.now() - startedAt
      });
    } finally {
      finishTaskTimer(startedAt);
    }
  };

  const handleAddToChat = (item: CanvasItem) => {
    focusImageInSidebarInput(item);
    addMessage('已锁定参考图，请输入编辑指令。', 'assistant');
  };

  const handleNavigateToImage = (item: CanvasItem) => {
    const latestItem = items.find(candidate => candidate.id === item.id) || item;

    handleCanvasSelectionChange([latestItem.id]);
    setPan(getPanForCanvasFrame(latestItem));
  };

  const handleCanvasBackgroundChange = (color: string) => {
    flushCanvasHistory();
    setCanvasBackgroundColor(color);
  };

  const handleAuthenticated = (session: AuthSession) => {
    setAuthSession(session);
    setHasLoadedCanvas(false);
    setCanvasSyncStatus('loading');
  };

  const handleLogout = async () => {
    flushQueuedCanvasSave();
    await logout();
    localStorage.removeItem(LAST_PROJECT_STORAGE_KEY);
    setAuthSession(null);
    resetApiConfigSession();
    setShowConfigModal(false);
    resetWorkspace();
  };

  const handleOpenConfigModal = async () => {
    if (isOpeningConfigModal) return;

    setIsOpeningConfigModal(true);
    try {
      await loadApiConfig();
    } catch (error) {
      console.warn('[api-config] lazy load failed', error);
      resetApiConfigSession();
    } finally {
      setIsOpeningConfigModal(false);
      setShowConfigModal(true);
    }
  };

  const handleConfigSaved = () => {
    setShowConfigModal(false);
  };

  const handleExportProjectImage = async (scope: CanvasExportScope = hasSelectedDownloadableImage ? 'selected' : 'delivery') => {
    if (isExportingProjectImage) return;

    setIsExportMenuOpen(false);
    setExportProjectImageError('');
    setIsExportingProjectImage(true);

    try {
      await exportCanvasProjectImage(items, selectedIds, currentProjectTitle, scope);
    } catch (error) {
      console.error('导出图片失败', error);
      const message = error instanceof Error ? error.message : '导出图片失败，请稍后再试';
      setExportProjectImageError(message);
      window.setTimeout(() => setExportProjectImageError(''), 4200);
    } finally {
      setIsExportingProjectImage(false);
    }
  };

  const exportOptions: { scope: CanvasExportScope; label: string; description: string; disabled?: boolean }[] = [
    {
      scope: 'selected',
      label: '选中图',
      description: hasSelectedDownloadableImage ? '只打包当前选中的成品图' : '请先在画布上选择成品图',
      disabled: !hasSelectedDownloadableImage
    },
    {
      scope: 'final',
      label: '最终图',
      description: '下载没有子派生版本的叶子图片'
    },
    {
      scope: 'derived',
      label: '全部派生图',
      description: '下载所有带 parentId 的重绘、裁剪版本'
    },
    {
      scope: 'delivery',
      label: '项目交付包',
      description: '打包当前项目所有成品图片'
    }
  ];

  const activeTaskCount = activeTaskStartedAts.length;
  const isThinking = activeTaskCount > 0;
  const activeTaskStartedAt = activeTaskCount > 0
    ? Math.min(...activeTaskStartedAts)
    : null;

  if (!isAuthReady) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#fcfcfc] font-sans text-sm font-black text-gray-400">
        正在恢复登录状态...
      </div>
    );
  }

  if (!authSession) {
    return <AuthPanel onAuthenticated={handleAuthenticated} />;
  }

  return (
    <div className="flex h-screen bg-[#fcfcfc] overflow-hidden font-sans text-gray-900">
      <div className="flex-1 relative flex flex-col">
        <div className="absolute left-4 top-4 z-30 flex items-center gap-2">
          <div className="flex items-center gap-1.5 rounded-2xl border border-gray-100 bg-white/90 p-1 shadow-[0_18px_48px_-28px_rgba(0,0,0,0.35)] backdrop-blur-2xl">
            <button
              onClick={handleOpenConfigModal}
              disabled={isOpeningConfigModal}
              className="flex h-9 w-9 items-center justify-center rounded-xl text-gray-400 transition-all hover:bg-gray-100 hover:text-gray-700 active:scale-95"
              title="API 配置"
            >
              {isOpeningConfigModal ? <Loader2 size={18} className="animate-spin" /> : <Settings size={18} />}
            </button>
            <select
              value={currentProjectId}
              onChange={(event) => handleProjectChange(event.target.value)}
              disabled={!hasLoadedCanvas || projects.length === 0}
              className="h-9 max-w-48 rounded-xl bg-gray-50 px-3 text-xs font-black text-gray-700 outline-none transition-all hover:bg-gray-100 disabled:opacity-50"
              title="切换项目画布"
            >
              {projects.map(project => (
                <option key={project.id} value={project.id}>
                  {project.title || '未命名项目'}
                </option>
              ))}
            </select>
            <button
              onClick={openCreateProjectModal}
              disabled={!hasLoadedCanvas}
              className="flex h-9 w-9 items-center justify-center rounded-xl text-gray-400 transition-all hover:bg-indigo-50 hover:text-indigo-600 active:scale-95 disabled:opacity-40"
              title="新建项目"
            >
              <FolderPlus size={17} />
            </button>
            <span
              className={`h-2.5 w-2.5 rounded-full ${
                canvasSyncStatus === 'error' ? 'bg-red-500' : canvasSyncStatus === 'saving' ? 'bg-amber-400' : 'bg-emerald-500'
              }`}
              title={canvasSyncText}
            />
          </div>
        </div>

        <div className="absolute right-4 top-4 z-30 flex items-center gap-2">
          <div className="flex items-center gap-1.5 rounded-2xl border border-gray-100 bg-white/90 p-1 shadow-[0_18px_48px_-28px_rgba(0,0,0,0.35)] backdrop-blur-2xl">
            <button
              onClick={() => setShowSidebar(!showSidebar)}
              className={`flex h-9 w-9 items-center justify-center rounded-xl transition-all active:scale-95 ${showSidebar ? 'text-indigo-600 bg-indigo-50' : 'text-gray-400 hover:bg-gray-100'}`}
              title={showSidebar ? "隐藏侧边栏" : "显示侧边栏"}
            >
              {showSidebar ? <PanelRightClose size={18} /> : <PanelRight size={18} />}
            </button>
            <div className="relative flex items-center">
              <button
                onClick={() => handleExportProjectImage()}
                disabled={isExportingProjectImage || !hasDownloadableImage}
                className="flex h-9 items-center gap-2 rounded-l-xl bg-zinc-900 px-4 text-xs font-black uppercase tracking-widest text-white transition-all hover:bg-zinc-800 active:scale-95 disabled:opacity-30"
                title="有选中图片时下载选中图；未选中时下载项目交付包"
              >
                {isExportingProjectImage ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />}
                {isExportingProjectImage ? '打包中' : '下载'}
              </button>
              <button
                type="button"
                onClick={() => setIsExportMenuOpen(prev => !prev)}
                disabled={isExportingProjectImage || !hasDownloadableImage}
                className="flex h-9 items-center justify-center rounded-r-xl border-l border-white/15 bg-zinc-900 px-2 text-white transition-all hover:bg-zinc-800 disabled:opacity-30"
                title="选择下载范围"
              >
                <ChevronDown size={14} className={`transition-transform ${isExportMenuOpen ? 'rotate-180' : ''}`} />
              </button>
              {isExportMenuOpen && (
                <div className="absolute right-0 top-11 z-[5000000] w-64 overflow-hidden rounded-2xl border border-gray-100 bg-white/95 p-1.5 shadow-2xl backdrop-blur-3xl">
                  {exportOptions.map(option => (
                    <button
                      key={option.scope}
                      type="button"
                      onClick={() => handleExportProjectImage(option.scope)}
                      disabled={option.disabled}
                      className="flex w-full flex-col rounded-xl px-3 py-2.5 text-left transition-all hover:bg-indigo-50 disabled:cursor-not-allowed disabled:opacity-40"
                    >
                      <span className="text-xs font-black text-gray-800">{option.label}</span>
                      <span className="mt-0.5 text-[10px] font-bold text-gray-400">{option.description}</span>
                    </button>
                  ))}
                </div>
              )}
            </div>
            <button
              onClick={handleLogout}
              className="flex h-9 w-9 items-center justify-center rounded-xl text-gray-300 transition-all hover:bg-red-50 hover:text-red-500 active:scale-95"
              title={`退出登录：${authSession.user.displayName || authSession.user.username}`}
            >
              <LogOut size={18} />
            </button>
          </div>
          <ProjectLinks />
        </div>

        {exportProjectImageError && (
          <div className="absolute right-4 top-16 z-[5000000] max-w-sm rounded-2xl border border-red-100 bg-white px-4 py-3 text-sm font-bold text-red-500 shadow-2xl">
            {exportProjectImageError}
          </div>
        )}

        <Canvas
          items={items}
          zoom={zoom}
          onZoomChange={handleZoomChange}
          pan={pan}
          onPanChange={setPan}
          backgroundColor={canvasBackgroundColor}
          onItemUpdate={handleUpdateItem}
          onItemDelete={(id) => handleDeleteItems([id])}
          onItemDeleteMultiple={handleDeleteItems}
          onItemAdd={(item) => {
            if (item.type === 'image' && item.status === 'completed' && isDataImageUrl(item.content)) {
              const uploadingItem: CanvasItem = {
                ...item,
                content: '',
                status: 'loading',
                label: `${item.label || '图片'} 上传中...`
              };
              setItems(prev => [...prev, uploadingItem]);
              setSelectedIds([item.id]);
              ensureCanvasImageAsset(item)
                .then((persistedItem) => {
                  const completedItem = {
                    ...item,
                    ...persistedItem,
                    status: 'completed' as const
                  };
                  setItems(prev => prev.map(candidate => candidate.id === item.id ? completedItem : candidate));
                  focusImageInSidebarInput(completedItem);
                })
                .catch((error) => {
                  const message = error instanceof Error ? error.message : '图片上传失败';
                  setItems(prev => prev.map(candidate => (
                    candidate.id === item.id
                      ? { ...candidate, status: 'error', label: message }
                      : candidate
                  )));
                });
              return;
            }

            setItems(prev => [...prev, item]);
            if (item.status !== 'loading') {
              setSelectedIds([item.id]);
              focusImageInSidebarInput(item);
            }
          }}
          onAddTextAt={addTextItem}
          onAddImageAt={addImageItem}
          onAddToChat={handleAddToChat}
          onChatMessage={handleCanvasChatMessage}
          canvasTool={canvasTool}
          onCanvasToolChange={setCanvasTool}
          onBeforeCanvasMutation={flushCanvasHistory}
          selectedIds={selectedIds}
          setSelectedIds={handleCanvasSelectionChange}
          onImagePromptRequest={focusImageInSidebarInput}
        />

        <CanvasUtilityDock
          items={items}
          selectedIds={selectedIds}
          pan={pan}
          zoom={zoom}
          showSidebar={showSidebar}
          backgroundColor={canvasBackgroundColor}
          onBackgroundColorChange={handleCanvasBackgroundChange}
          onNavigateToItem={handleNavigateToImage}
          onPanChange={setPan}
        />

        <Toolbar
          zoom={zoom}
          onZoomChange={handleZoomChange}
          onResetView={() => { setZoom(1); setPan({ x: window.innerWidth / 4, y: window.innerHeight / 4 }); }}
          onAddImage={addImageItem}
          onAddText={() => setCanvasTool(currentTool => currentTool === 'text' ? 'select' : 'text')}
          activeTool={canvasTool}
          onToolChange={setCanvasTool}
          canUndo={canvasHistoryState.canUndo}
          canRedo={canvasHistoryState.canRedo}
          onUndo={undoCanvas}
          onRedo={redoCanvas}
          canAutoArrangeImages={items.filter(isArrangeableImageItem).length > 1}
          onAutoArrangeImages={handleAutoArrangeImages}
        />
      </div>

      <div className={`transition-all duration-300 ease-in-out ${showSidebar ? 'w-96 opacity-100 translate-x-0' : 'w-0 opacity-0 translate-x-full overflow-hidden'}`}>
        <Sidebar
          messages={messages}
          isThinking={isThinking}
          activeTaskStartedAt={activeTaskStartedAt}
          activeTaskCount={activeTaskCount}
          onSendMessage={handleSidebarSendMessage}
          contextImage={contextImage}
          promptSeed={sidebarPromptSeed}
          inputResetKey={sidebarInputResetKey}
          imageItems={items.filter(item => item.type === 'image' && !!item.content)}
          onSelectContextImage={setContextImage}
          onClearContextImage={handleClearSidebarContextImage}
          onNavigateToImage={handleNavigateToImage}
        />
      </div>

      <ConfigModal
        isOpen={showConfigModal}
        required={false}
        onSaved={handleConfigSaved}
        onClose={() => setShowConfigModal(false)}
      />

      <CreateProjectModal
        isOpen={isCreateProjectModalOpen}
        title={newProjectTitle}
        isCreating={isCreatingProject}
        error={createProjectError}
        onTitleChange={setNewProjectTitle}
        onClose={() => {
          if (!isCreatingProject) {
            setIsCreateProjectModalOpen(false);
            setCreateProjectError('');
          }
        }}
        onSubmit={handleCreateProject}
      />
    </div>
  );
}

export default App;
