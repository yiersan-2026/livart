
import React, { useEffect, useRef, useState } from 'react';
import { Trash2, Hammer, PanelRightClose, PanelRight, Settings, FolderPlus, LogOut, UserRound } from 'lucide-react';
import { CanvasItem, ChatMessage } from './types';
import AuthPanel from './components/AuthPanel';
import Canvas from './components/Canvas';
import Sidebar from './components/Sidebar';
import Toolbar from './components/Toolbar';
import ConfigModal from './components/ConfigModal';
import { generateImage, generateWorkflowImage } from './services/gemini';
import {
  CanvasPersistenceState,
  CanvasProject,
  createCanvasProject,
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
import { hasStoredApiConfig } from './services/config';

const MIN_ZOOM = 0.1;
const MAX_ZOOM = 5;
const SIDEBAR_WIDTH = 384;
const LAST_PROJECT_STORAGE_KEY = 'livart_last_project_id';

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

function App() {
  const [items, setItems] = useState<CanvasItem[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([createWelcomeMessage()]);
  const [isThinking, setIsThinking] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: window.innerWidth / 4, y: window.innerHeight / 4 });
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [showSidebar, setShowSidebar] = useState(true);
  const [contextImage, setContextImage] = useState<CanvasItem | null>(null);
  const [apiConfigReady, setApiConfigReady] = useState(() => hasStoredApiConfig());
  const [showConfigModal, setShowConfigModal] = useState(() => !hasStoredApiConfig());
  const [authSession, setAuthSession] = useState<AuthSession | null>(() => getStoredAuthSession());
  const [isAuthReady, setIsAuthReady] = useState(false);
  const [hasLoadedCanvas, setHasLoadedCanvas] = useState(false);
  const [canvasSyncStatus, setCanvasSyncStatus] = useState<'loading' | 'saving' | 'saved' | 'error'>('loading');
  const [projects, setProjects] = useState<CanvasProject[]>([]);
  const [currentProjectId, setCurrentProjectId] = useState('');
  const [currentProjectTitle, setCurrentProjectTitle] = useState('默认画布');
  const saveTimerRef = useRef<number | null>(null);
  const pendingSaveRef = useRef<PendingCanvasSave | null>(null);
  const isSavingCanvasRef = useRef(false);
  const saveRevisionRef = useRef(Date.now());

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
      .then(() => {
        if (!pendingSaveRef.current) {
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

    saveTimerRef.current = window.setTimeout(flushQueuedCanvasSave, 800);

    return () => {
      if (saveTimerRef.current) {
        window.clearTimeout(saveTimerRef.current);
      }
    };
  }, [items, messages, zoom, pan, hasLoadedCanvas, currentProjectId, currentProjectTitle, authSession]);

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

  const handleCreateProject = async () => {
    const title = window.prompt('请输入项目名称', `新项目 ${projects.length + 1}`);
    if (title === null) return;

    flushQueuedCanvasSave();
    setHasLoadedCanvas(false);
    setCanvasSyncStatus('loading');

    try {
      const created = await createCanvasProject(title.trim() || `新项目 ${projects.length + 1}`);
      setProjects(prev => [created.project, ...prev]);
      applyLoadedProject(created.project, created.state);
      setCanvasSyncStatus('saved');
    } catch (error) {
      console.warn('[canvas-persistence] create project failed', error);
      setCanvasSyncStatus('error');
    } finally {
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

  const addMessage = (text: string, role: 'user' | 'assistant') => {
    setMessages(prev => [...prev, { id: Math.random().toString(36).substr(2, 9), role, text, timestamp: Date.now() }]);
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

  const handleSidebarSendMessage = async (text: string) => {
    addMessage(text, 'user');
    setIsThinking(true);
    
    // 在画布中心先创建一个加载占位
    const newId = Math.random().toString(36).substr(2, 9);
    const initialWidth = contextImage ? contextImage.width : 512;
    const initialHeight = contextImage ? contextImage.height : 512;
    
    const newItem: CanvasItem = {
      id: newId,
      type: 'image',
      content: '',
      x: (-pan.x + window.innerWidth/2) / zoom - initialWidth / 2,
      y: (-pan.y + window.innerHeight/2) / zoom - initialHeight / 2,
      width: initialWidth,
      height: initialHeight,
      status: 'loading',
      label: contextImage ? 'AI 编辑中...' : 'AI 生成中...',
      zIndex: 60,
      layers: []
    };
    
    setItems(prev => [...prev, newItem]);
    setSelectedIds([newId]);

    try {
      let resultImg: string;
      if (contextImage) {
        // 执行图像编辑
        resultImg = await generateWorkflowImage(text, contextImage.content);
        addMessage('已根据参考图完成编辑。', 'assistant');
      } else {
        // 直接文本生成图片
        resultImg = await generateImage(text, 'none');
        addMessage('已为你生成新的画面。', 'assistant');
      }
      
      setItems(prev => prev.map(i => i.id === newId ? { 
        ...i, 
        content: resultImg, 
        status: 'completed',
        label: text.substring(0, 10) + (text.length > 10 ? '...' : '')
      } : i));
      
      setContextImage(null);
    } catch (error) {
      const errorMessage = error instanceof Error ? error.message : '未知错误';
      setItems(prev => prev.map(i => i.id === newId ? { ...i, status: 'error' } : i));
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
    resetWorkspace();
  };

  const handleConfigSaved = () => {
    setApiConfigReady(true);
    setShowConfigModal(false);
  };

  if (!isAuthReady) {
    return (
      <div className="flex h-screen items-center justify-center bg-[#fcfcfc] font-sans text-sm font-black text-gray-400">
        正在恢复登录状态...
      </div>
    );
  }

  if (!authSession) {
    return (
      <>
        <AuthPanel onAuthenticated={handleAuthenticated} />
        <ConfigModal
          isOpen={showConfigModal || !apiConfigReady}
          required={!apiConfigReady}
          onSaved={handleConfigSaved}
          onClose={() => {
            if (apiConfigReady) {
              setShowConfigModal(false);
            }
          }}
        />
      </>
    );
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
                onClick={handleCreateProject}
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
            <button onClick={() => { if(confirm('确定清空画布吗？')) { setItems([]); setMessages([]); setSelectedIds([]); setContextImage(null); } }} className="p-2 text-gray-300 hover:text-red-500 rounded-xl transition-all"><Trash2 size={18} /></button>
            <div className="h-4 w-[1px] bg-gray-100 mx-1" />
            <button className="px-5 py-2 text-xs font-black bg-black text-white rounded-xl shadow-lg hover:opacity-90 active:scale-95 transition-all uppercase tracking-widest">
              交付作品
            </button>
          </div>
        </header>

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
        />
      </div>

      <ConfigModal
        isOpen={showConfigModal || !apiConfigReady}
        required={!apiConfigReady}
        onSaved={handleConfigSaved}
        onClose={() => {
          if (apiConfigReady) {
            setShowConfigModal(false);
          }
        }}
      />
    </div>
  );
}

export default App;
