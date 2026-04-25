
import React, { useEffect, useRef, useState } from 'react';
import { Hammer, PanelRightClose, PanelRight, Settings, FolderPlus, LogOut, UserRound, Loader2, X, Download } from 'lucide-react';
import type { CanvasItem, ChatMessage, ImageAspectRatio } from './types';
import AuthPanel from './components/AuthPanel';
import Canvas from './components/Canvas';
import Sidebar from './components/Sidebar';
import Toolbar from './components/Toolbar';
import ConfigModal from './components/ConfigModal';
import {
  canUseImageJobs,
  editImage,
  generateImage,
  submitImageEditJob,
  submitImageGenerationJob,
  waitForImageJob
} from './services/gemini';
import {
  DEFAULT_GENERATED_IMAGE_LONG_SIDE,
  centerFrameOnRect,
  getAspectRatioFrame,
  getImageFrameFromSource,
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
import { loadApiConfig, resetApiConfigSession } from './services/config';
import { exportCanvasProjectImage } from './services/canvasExport';
import {
  buildImageReferenceRoleContext,
  buildReferencedImageEditPrompt,
  resolveEditReferencesWithAi
} from './services/imageReferences';

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 5;
const SIDEBAR_WIDTH = 384;
const LAST_PROJECT_STORAGE_KEY = 'livart_last_project_id';

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
  const [isThinking, setIsThinking] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: window.innerWidth / 4, y: window.innerHeight / 4 });
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [showSidebar, setShowSidebar] = useState(true);
  const [contextImage, setContextImage] = useState<CanvasItem | null>(null);
  const [apiConfigReady, setApiConfigReady] = useState(false);
  const [isApiConfigLoaded, setIsApiConfigLoaded] = useState(false);
  const [showConfigModal, setShowConfigModal] = useState(false);
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
  const [exportProjectImageError, setExportProjectImageError] = useState('');
  const saveTimerRef = useRef<number | null>(null);
  const pendingSaveRef = useRef<PendingCanvasSave | null>(null);
  const isSavingCanvasRef = useRef(false);
  const saveRevisionRef = useRef(Date.now());
  const resumedImageJobIdsRef = useRef<Set<string>>(new Set());
  const hasPendingImageJob = items.some(item => item.type === 'image' && item.status === 'loading' && !!item.imageJobId);
  const hasDownloadableImage = items.some(item => item.type === 'image' && item.status === 'completed' && !!item.content);

  const resetWorkspace = () => {
    setItems([]);
    setMessages([createWelcomeMessage()]);
    setZoom(1);
    setPan({ x: window.innerWidth / 4, y: window.innerHeight / 4 });
    setSelectedIds([]);
    setContextImage(null);
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
    setSelectedIds([]);
    setContextImage(null);
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
    if (!isAuthReady) return;

    let isMounted = true;

    if (!authSession) {
      resetApiConfigSession();
      setApiConfigReady(false);
      setIsApiConfigLoaded(false);
      setShowConfigModal(false);
      return () => {
        isMounted = false;
      };
    }

    setIsApiConfigLoaded(false);
    loadApiConfig()
      .then((config) => {
        if (!isMounted) return;
        const hasConfig = !!config;
        setApiConfigReady(hasConfig);
        setShowConfigModal(!hasConfig);
      })
      .catch((error) => {
        console.warn('[api-config] load failed', error);
        if (isMounted) {
          setApiConfigReady(false);
          setShowConfigModal(true);
        }
      })
      .finally(() => {
        if (isMounted) {
          setIsApiConfigLoaded(true);
        }
      });

    return () => {
      isMounted = false;
    };
  }, [isAuthReady, authSession?.token]);

  useEffect(() => {
    if (!isThinking && !hasPendingImageJob) return;

    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [isThinking, hasPendingImageJob]);

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
        selectedIds: []
      }
    };

    saveTimerRef.current = window.setTimeout(flushQueuedCanvasSave, hasPendingImageJob ? 50 : 800);

    return () => {
      if (saveTimerRef.current) {
        window.clearTimeout(saveTimerRef.current);
      }
    };
  }, [items, messages, zoom, pan, hasLoadedCanvas, currentProjectId, currentProjectTitle, authSession]);

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
        .then(async (resultImg) => {
          const finalFrame = await getImageFrameFromSource(
            resultImg,
            item.width,
            item.height,
            Math.max(item.width, item.height)
          );

          setItems(prev => prev.map(candidate => {
            if (candidate.id !== item.id) return candidate;
            const centeredFrame = centerFrameOnRect(candidate, finalFrame);
            return {
              ...candidate,
              ...centeredFrame,
              content: resultImg,
              status: 'completed',
              imageJobId: undefined
            };
          }));
        })
        .catch((error) => {
          const message = error instanceof Error ? error.message : '图片任务恢复失败';
          setItems(prev => prev.map(candidate => (
            candidate.id === item.id
              ? { ...candidate, status: 'error', label: message, imageJobId: undefined }
              : candidate
          )));
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
          content: content,
          x: (dropX !== undefined ? dropX : (-pan.x + window.innerWidth/2) / zoom) - width / 2,
          y: (dropY !== undefined ? dropY : (-pan.y + window.innerHeight/2) / zoom) - height / 2,
          width: width,
          height: height,
          status: 'completed',
          label: file.name,
          zIndex: 50,
          layers: []
        };
        setItems(prev => [...prev, newItem]);
        setSelectedIds([newId]);
      };
      img.src = content;
    };
    reader.readAsDataURL(file);
  };

  const addTextItem = (x?: number, y?: number) => {
    const newId = Math.random().toString(36).substr(2, 9);
    const width = 200;
    const height = 100;
    const newItem: CanvasItem = {
      id: newId,
      type: 'text',
      content: '',
      x: x !== undefined ? x : ((-pan.x + window.innerWidth/2) / zoom - width / 2),
      y: y !== undefined ? y : ((-pan.y + window.innerHeight/2) / zoom - height / 2),
      width: width,
      height: height,
      status: 'completed',
      label: '文本组件',
      zIndex: 200,
      layers: []
    };
    setItems(prev => [...prev, newItem]);
    setSelectedIds([newId]);
  };

  const addMessage = (text: string, role: 'user' | 'assistant', options: Pick<ChatMessage, 'imageIds'> = {}) => {
    setMessages(prev => [...prev, {
      id: Math.random().toString(36).substr(2, 9),
      role,
      text,
      timestamp: Date.now(),
      ...options
    }]);
  };

  const handleUpdateItem = (id: string, updates: Partial<CanvasItem>) => {
    setItems(prev => prev.map(item => item.id === id ? { ...item, ...updates } : item));
  };

  const handleDeleteItems = (ids: string[]) => {
    setItems(prev => prev.filter(i => !ids.includes(i.id)));
    setSelectedIds([]);
    if (contextImage && ids.includes(contextImage.id)) {
      setContextImage(null);
    }
  };

  const handleSidebarSendMessage = async (text: string, aspectRatio: ImageAspectRatio = 'auto') => {
    addMessage(text, 'user');
    setIsThinking(true);

    const editReferences = await resolveEditReferencesWithAi(text, contextImage, items);
    const editBaseImage = editReferences[0] || null;
    const editReferenceImages = editReferences.slice(1);
    const effectiveAspectRatio = editBaseImage && aspectRatio === 'auto'
      ? inferAspectRatioFromDimensions(editBaseImage.width, editBaseImage.height)
      : aspectRatio;
    
    const newId = Math.random().toString(36).substr(2, 9);
    const siblingCount = editBaseImage ? items.filter(item => item.parentId === editBaseImage.id).length : 0;
    const fallbackWidth = editBaseImage ? editBaseImage.width : DEFAULT_GENERATED_IMAGE_LONG_SIDE;
    const fallbackHeight = editBaseImage ? editBaseImage.height : DEFAULT_GENERATED_IMAGE_LONG_SIDE;
    const maxLongSide = editBaseImage
      ? Math.max(editBaseImage.width, editBaseImage.height)
      : DEFAULT_GENERATED_IMAGE_LONG_SIDE;
    const initialFrame = getAspectRatioFrame(effectiveAspectRatio, fallbackWidth, fallbackHeight, maxLongSide);
    const canvasCenterX = (-pan.x + window.innerWidth / 2) / zoom;
    const canvasCenterY = (-pan.y + window.innerHeight / 2) / zoom;
    
    const newItem: CanvasItem = {
      id: newId,
      type: 'image',
      content: '',
      x: editBaseImage
        ? editBaseImage.x + editBaseImage.width + 120 + siblingCount * 36
        : canvasCenterX - initialFrame.width / 2,
      y: editBaseImage
        ? editBaseImage.y + siblingCount * 36
        : canvasCenterY - initialFrame.height / 2,
      width: initialFrame.width,
      height: initialFrame.height,
      status: 'loading',
      label: editBaseImage ? 'AI 编辑中...' : 'AI 生成中...',
      zIndex: Math.max(60, ...items.map(item => item.zIndex || 0)) + 1,
      parentId: editBaseImage?.id,
      prompt: text,
      layers: []
    };
    
    setItems(prev => [...prev, newItem]);
    setSelectedIds([newId]);

    try {
      let resultImg: string;
      if (editBaseImage) {
        const persistedEditImages = await Promise.all(
          [editBaseImage, ...editReferenceImages].map(item => ensureCanvasImageAsset(item))
        );
        const persistedBaseImage = persistedEditImages[0];
        const persistedReferenceImages = persistedEditImages.slice(1);
        const persistedById = new Map(persistedEditImages.map(item => [item.id, item]));
        setItems(prev => prev.map(item => persistedById.get(item.id) || item));

        const editPrompt = buildReferencedImageEditPrompt(text, persistedBaseImage, persistedReferenceImages, { allItems: items });
        const referenceContents = persistedReferenceImages.map(item => item.content);
        const imageContext = buildImageReferenceRoleContext(text, persistedBaseImage, persistedReferenceImages, { allItems: items });
        const editOptions = {
          imageAssetId: getCanvasItemAssetId(persistedBaseImage),
          referenceAssetIds: persistedReferenceImages.map(getCanvasItemAssetId).filter(Boolean),
          imageContext
        };
        // 执行图像编辑
        if (canUseImageJobs()) {
          const job = await submitImageEditJob(
            editPrompt,
            persistedBaseImage.content,
            undefined,
            effectiveAspectRatio,
            referenceContents,
            editOptions
          );
          setItems(prev => prev.map(i => i.id === newId ? { ...i, imageJobId: job.jobId } : i));
          resultImg = await waitForImageJob(job.jobId);
        } else {
          resultImg = await editImage(
            editPrompt,
            persistedBaseImage.content,
            undefined,
            effectiveAspectRatio,
            referenceContents,
            editOptions
          );
        }
      } else {
        // 直接文本生成图片
        if (canUseImageJobs()) {
          const job = await submitImageGenerationJob(text, aspectRatio);
          setItems(prev => prev.map(i => i.id === newId ? { ...i, imageJobId: job.jobId } : i));
          resultImg = await waitForImageJob(job.jobId);
        } else {
          resultImg = await generateImage(text, 'none', aspectRatio);
        }
      }

      const finalFrame = editBaseImage
        ? initialFrame
        : await getImageFrameFromSource(
          resultImg,
          initialFrame.width,
          initialFrame.height,
          Math.max(initialFrame.width, initialFrame.height)
        );
      
      setItems(prev => prev.map(i => {
        if (i.id !== newId) return i;
        const centeredFrame = centerFrameOnRect(i, finalFrame);
        return {
          ...i,
          ...centeredFrame,
          content: resultImg,
          status: 'completed',
          imageJobId: undefined,
          label: text.substring(0, 10) + (text.length > 10 ? '...' : '')
        };
      }));

      const completionText = editBaseImage
        ? `${editReferenceImages.length > 0 ? '已根据多张引用图完成编辑' : '已根据参考图完成编辑'}：@${newId}`
        : `已为你生成新的画面：@${newId}`;
      addMessage(completionText, 'assistant', { imageIds: [newId] });
      
      setContextImage(null);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      setItems(prev => prev.map(i => i.id === newId ? { ...i, status: 'error', imageJobId: undefined } : i));
      addMessage(`出错了，没能完成生成：${errorMessage}`, 'assistant');
    } finally {
      setIsThinking(false);
    }
  };

  const handleAddToChat = (item: CanvasItem) => {
    setContextImage(item);
    setShowSidebar(true);
    addMessage('已锁定参考图，请输入编辑指令。', 'assistant');
  };

  const handleNavigateToImage = (item: CanvasItem) => {
    const latestItem = items.find(candidate => candidate.id === item.id) || item;
    const availableWidth = window.innerWidth - (showSidebar ? SIDEBAR_WIDTH : 0);
    const targetCenterX = latestItem.x + latestItem.width / 2;
    const targetCenterY = latestItem.y + latestItem.height / 2;

    setSelectedIds([latestItem.id]);
    setPan({
      x: availableWidth / 2 - targetCenterX * zoom,
      y: window.innerHeight / 2 - targetCenterY * zoom
    });
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
    setApiConfigReady(false);
    setIsApiConfigLoaded(false);
    setShowConfigModal(false);
    resetWorkspace();
  };

  const handleConfigSaved = () => {
    setApiConfigReady(true);
    setIsApiConfigLoaded(true);
    setShowConfigModal(false);
  };

  const handleExportProjectImage = async () => {
    if (isExportingProjectImage) return;

    setExportProjectImageError('');
    setIsExportingProjectImage(true);

    try {
      await exportCanvasProjectImage(items, selectedIds, currentProjectTitle);
    } catch (error) {
      console.error('导出图片失败', error);
      const message = error instanceof Error ? error.message : '导出图片失败，请稍后再试';
      setExportProjectImageError(message);
      window.setTimeout(() => setExportProjectImageError(''), 4200);
    } finally {
      setIsExportingProjectImage(false);
    }
  };

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
        <header className="absolute top-0 left-0 right-0 h-16 bg-white/80 backdrop-blur-3xl border-b border-gray-100 px-6 flex items-center justify-between z-30">
          <div className="flex items-center gap-4">
            <button
              onClick={() => setShowConfigModal(true)}
              className="p-2 text-gray-400 hover:text-gray-600 hover:bg-gray-100 rounded-xl transition-all"
              title="API 配置"
            >
              <Settings size={18} />
            </button>
            <div className="flex items-center gap-3">
              <div className="w-10 h-10 bg-black rounded-xl flex items-center justify-center shadow-lg">
                <Hammer className="text-white" size={18} />
              </div>
              <div className="flex flex-col -gap-1 text-left">
                <span className="font-black text-lg tracking-tighter text-gray-900 leading-none">livart</span>
                <span className="text-[7px] font-black uppercase tracking-widest text-gray-400">AI Canvas</span>
              </div>
              <span className={`rounded-full px-2.5 py-1 text-[10px] font-black ${
                canvasSyncStatus === 'error' ? 'bg-red-50 text-red-500' : 'bg-emerald-50 text-emerald-600'
              }`}>
                {canvasSyncText}
              </span>
            </div>
            <div className="flex items-center gap-2 rounded-2xl border border-gray-100 bg-white/70 px-2 py-1.5 shadow-sm">
              <select
                value={currentProjectId}
                onChange={(event) => handleProjectChange(event.target.value)}
                disabled={!hasLoadedCanvas || projects.length === 0}
                className="max-w-56 bg-transparent text-xs font-black text-gray-700 outline-none disabled:opacity-50"
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
                className="p-1.5 text-gray-400 hover:text-indigo-600 hover:bg-indigo-50 rounded-xl transition-all disabled:opacity-40"
                title="新建项目"
              >
                <FolderPlus size={16} />
              </button>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <div className="hidden items-center gap-2 rounded-2xl border border-gray-100 bg-white/70 px-3 py-2 text-xs font-black text-gray-500 shadow-sm md:flex">
              <UserRound size={15} className="text-indigo-500" />
              <span className="max-w-28 truncate">{authSession.user.displayName || authSession.user.username}</span>
            </div>
            <button
              onClick={handleLogout}
              className="p-2 text-gray-300 hover:text-red-500 hover:bg-red-50 rounded-xl transition-all"
              title="退出登录"
            >
              <LogOut size={18} />
            </button>
            <div className="h-4 w-[1px] bg-gray-100 mx-1" />
            <button 
              onClick={() => setShowSidebar(!showSidebar)} 
              className={`p-2 rounded-xl transition-all ${showSidebar ? 'text-indigo-600 bg-indigo-50' : 'text-gray-400 hover:bg-gray-100'}`}
              title={showSidebar ? "隐藏侧边栏" : "显示侧边栏"}
            >
              {showSidebar ? <PanelRightClose size={18} /> : <PanelRight size={18} />}
            </button>
            <div className="h-4 w-[1px] bg-gray-100 mx-1" />
            <button
              onClick={handleExportProjectImage}
              disabled={isExportingProjectImage || !hasDownloadableImage}
              className="flex items-center gap-2 px-5 py-2 text-xs font-black bg-black text-white rounded-xl shadow-lg hover:opacity-90 active:scale-95 transition-all uppercase tracking-widest disabled:opacity-30"
              title="打包下载选中的成品图；未选中时打包下载全部成品图"
            >
              {isExportingProjectImage ? <Loader2 size={14} className="animate-spin" /> : <Download size={14} />}
              {isExportingProjectImage ? '打包中' : '下载'}
            </button>
          </div>
        </header>

        {exportProjectImageError && (
          <div className="absolute right-6 top-20 z-[5000000] max-w-sm rounded-2xl border border-red-100 bg-white px-4 py-3 text-sm font-bold text-red-500 shadow-2xl">
            {exportProjectImageError}
          </div>
        )}

        <Canvas 
          items={items} 
          zoom={zoom} 
          onZoomChange={handleZoomChange}
          pan={pan} 
          onPanChange={setPan} 
          onItemUpdate={handleUpdateItem}
          onItemDelete={(id) => handleDeleteItems([id])}
          onItemDeleteMultiple={handleDeleteItems}
          onItemAdd={(item) => { setItems(prev => [...prev, item]); setSelectedIds([item.id]); }}
          onAddTextAt={addTextItem}
          onAddImageAt={addImageItem}
          onAddToChat={handleAddToChat}
          selectedIds={selectedIds} 
          setSelectedIds={setSelectedIds}
        />
        
        <Toolbar 
          zoom={zoom} 
          onZoomChange={handleZoomChange}
          onResetView={() => { setZoom(1); setPan({ x: window.innerWidth / 4, y: window.innerHeight / 4 }); }}
          onAddWorkflow={addWorkflow}
          onAddImage={addImageItem}
          onAddText={() => addTextItem()}
        />
      </div>

      <div className={`transition-all duration-300 ease-in-out ${showSidebar ? 'w-96 opacity-100 translate-x-0' : 'w-0 opacity-0 translate-x-full overflow-hidden'}`}>
        <Sidebar
          messages={messages}
          isThinking={isThinking}
          onSendMessage={handleSidebarSendMessage}
          contextImage={contextImage}
          imageItems={items.filter(item => item.type === 'image' && !!item.content)}
          onSelectContextImage={setContextImage}
          onClearContextImage={() => setContextImage(null)}
          onNavigateToImage={handleNavigateToImage}
        />
      </div>

      <ConfigModal
        isOpen={isApiConfigLoaded && (showConfigModal || !apiConfigReady)}
        required={!apiConfigReady}
        onSaved={handleConfigSaved}
        onClose={() => {
          if (apiConfigReady) {
            setShowConfigModal(false);
          }
        }}
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
