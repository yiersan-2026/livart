
import React, { useState } from 'react';
import { Trash2, Hammer, PanelRightClose, PanelRight, Settings } from 'lucide-react';
import { CanvasItem, PlanStep, ChatMessage } from './types';
import Canvas from './components/Canvas';
import Sidebar from './components/Sidebar';
import Toolbar from './components/Toolbar';
import ConfigModal from './components/ConfigModal';
import { generateImage, generateWorkflowImage } from './services/gemini';

function App() {
  const [items, setItems] = useState<CanvasItem[]>([]);
  const [messages, setMessages] = useState<ChatMessage[]>([{
    id: 'welcome',
    role: 'assistant',
    text: '你好！我是灵匠助手。请直接告诉我你想要生成的画面，或者右键图片添加到对话进行编辑。',
    timestamp: Date.now()
  }]);
  const [isThinking, setIsThinking] = useState(false);
  const [zoom, setZoom] = useState(1);
  const [pan, setPan] = useState({ x: window.innerWidth / 4, y: window.innerHeight / 4 });
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [showSidebar, setShowSidebar] = useState(true);
  const [contextImage, setContextImage] = useState<CanvasItem | null>(null);
  const [showConfigModal, setShowConfigModal] = useState(false);

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

  const addImageItem = (file: File) => {
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
          x: (-pan.x + window.innerWidth/2) / zoom - width / 2,
          y: (-pan.y + window.innerHeight/2) / zoom - height / 2,
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
      setItems(prev => prev.map(i => i.id === newId ? { ...i, status: 'error' } : i));
      addMessage('出错了，没能完成生成，请稍后再试。', 'assistant');
    } finally {
      setIsThinking(false);
    }
  };

  const handleAddToChat = (item: CanvasItem) => {
    setContextImage(item);
    setShowSidebar(true);
    addMessage('已锁定参考图，请输入编辑指令。', 'assistant');
  };

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
                <span className="font-black text-lg tracking-tighter text-gray-900 leading-none">灵匠</span>
                <span className="text-[7px] font-black uppercase tracking-widest text-gray-400">Artisan Lab</span>
              </div>
            </div>
          </div>
          <div className="flex items-center gap-3">
            <button 
              onClick={() => setShowSidebar(!showSidebar)} 
              className={`p-2 rounded-xl transition-all ${showSidebar ? 'text-indigo-600 bg-indigo-50' : 'text-gray-400 hover:bg-gray-100'}`}
              title={showSidebar ? "隐藏侧边栏" : "显示侧边栏"}
            >
              {showSidebar ? <PanelRightClose size={18} /> : <PanelRight size={18} />}
            </button>
            <div className="h-4 w-[1px] bg-gray-100 mx-1" />
            <button onClick={() => { if(confirm('确定清空匠心画布吗？')) { setItems([]); setMessages([]); setSelectedIds([]); setContextImage(null); } }} className="p-2 text-gray-300 hover:text-red-500 rounded-xl transition-all"><Trash2 size={18} /></button>
            <div className="h-4 w-[1px] bg-gray-100 mx-1" />
            <button className="px-5 py-2 text-xs font-black bg-black text-white rounded-xl shadow-lg hover:opacity-90 active:scale-95 transition-all uppercase tracking-widest">
              交付作品
            </button>
          </div>
        </header>

        <Canvas 
          items={items} 
          zoom={zoom} 
          onZoomChange={setZoom}
          pan={pan} 
          onPanChange={setPan} 
          onItemUpdate={handleUpdateItem}
          onItemDelete={(id) => handleDeleteItems([id])}
          onItemDeleteMultiple={handleDeleteItems}
          onItemAdd={(item) => { setItems(prev => [...prev, item]); setSelectedIds([item.id]); }}
          onAddTextAt={addTextItem}
          onAddToChat={handleAddToChat}
          selectedIds={selectedIds} 
          setSelectedIds={setSelectedIds}
        />
        
        <Toolbar 
          zoom={zoom} 
          onZoomChange={setZoom} 
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
          onClearContextImage={() => setContextImage(null)}
        />
      </div>

      <ConfigModal isOpen={showConfigModal} onClose={() => setShowConfigModal(false)} />
    </div>
  );
}

export default App;
