
import React, { useState, useRef, useEffect, useCallback } from 'react';
import { CanvasItem } from '../types';
import { 
  Loader2, Trash2, Type, 
  Sparkles, ChevronUp, ChevronDown, 
  MousePointer2, Eraser, 
  MessageSquarePlus, Pencil,
  Copy, Layers
} from 'lucide-react';
import { generateWorkflowImage } from '../services/gemini';

interface CanvasProps {
  items: CanvasItem[];
  zoom: number;
  onZoomChange: (newZoom: number) => void;
  pan: { x: number; y: number };
  onPanChange: (pan: { x: number; y: number }) => void;
  onItemUpdate: (id: string, updates: Partial<CanvasItem>) => void;
  onItemDelete: (id: string) => void;
  onItemDeleteMultiple: (ids: string[]) => void;
  onItemAdd: (item: CanvasItem) => void; 
  onAddTextAt: (x: number, y: number) => void;
  onAddToChat: (item: CanvasItem) => void;
  selectedIds: string[];
  setSelectedIds: (ids: string[]) => void;
}

type ResizeDirection = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw';

const Canvas: React.FC<CanvasProps> = ({ 
  items, zoom, onZoomChange, pan, onPanChange, onItemUpdate, onItemDelete, onItemDeleteMultiple, onItemAdd, onAddTextAt, onAddToChat, selectedIds, setSelectedIds 
}) => {
  const [dragState, setDragState] = useState<{ id: string, startX: number, startY: number } | null>(null);
  const [resizeState, setResizeState] = useState<{ 
    id: string, direction: ResizeDirection, startX: number, startY: number, 
    startW: number, startH: number, startItemX: number, startItemY: number
  } | null>(null);
  
  const [isPanning, setIsPanning] = useState(false);
  const [isSpacePressed, setIsSpacePressed] = useState(false);
  const [lastMousePos, setLastMousePos] = useState({ x: 0, y: 0 });
  const [selectionBox, setSelectionBox] = useState<{ startX: number, startY: number, x: number, y: number, w: number, h: number } | null>(null);

  // 绘图工具状态
  const [activeTool, setActiveTool] = useState<'select' | 'brush' | 'eraser'>('select');
  const [brushSize, setBrushSize] = useState(8);
  const [isDrawing, setIsDrawing] = useState(false);
  const drawingCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const [frameworkPrompt, setFrameworkPrompt] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);

  const containerRef = useRef<HTMLDivElement>(null);
  const selectedItem = items.find(i => selectedIds.length === 1 && i.id === selectedIds[0]);

  // 全局右键菜单状态
  const [contextMenu, setContextMenu] = useState<{ x: number, y: number, id: string } | null>(null);

  // 修复假死：当选中项改变时，确保重置绘图状态，防止 Ref 冲突或状态死循环
  useEffect(() => {
    if (selectedItem?.type !== 'workflow' || activeTool === 'select') {
      setIsDrawing(false);
      if (activeTool !== 'select') setActiveTool('select');
    }
  }, [selectedIds]);

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (e.code === 'Space' && !['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement).tagName)) {
        setIsSpacePressed(true);
        if (e.target === document.body) e.preventDefault();
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && selectedIds.length > 0 && !['INPUT', 'TEXTAREA'].includes((e.target as HTMLElement).tagName)) {
        onItemDeleteMultiple(selectedIds);
      }
    };
    const handleKeyUp = (e: KeyboardEvent) => {
      if (e.code === 'Space') setIsSpacePressed(false);
    };
    const handleClickOutside = () => setContextMenu(null);

    window.addEventListener('keydown', handleKeyDown);
    window.addEventListener('keyup', handleKeyUp);
    window.addEventListener('click', handleClickOutside);
    return () => {
      window.removeEventListener('keydown', handleKeyDown);
      window.removeEventListener('keyup', handleKeyUp);
      window.removeEventListener('click', handleClickOutside);
    };
  }, [selectedIds, onItemDeleteMultiple]);

  useEffect(() => {
    const handleWheel = (e: WheelEvent) => {
      if (e.ctrlKey) {
        e.preventDefault();
        const delta = -e.deltaY;
        onZoomChange(Math.min(Math.max(0.1, zoom + delta * 0.001), 5));
      }
    };
    containerRef.current?.addEventListener('wheel', handleWheel, { passive: false });
    return () => containerRef.current?.removeEventListener('wheel', handleWheel);
  }, [zoom, onZoomChange]);

  const handleMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    if (isSpacePressed) {
      setIsPanning(true);
      setLastMousePos({ x: e.clientX, y: e.clientY });
      return;
    }

    // 画笔逻辑：置顶层画布捕获
    if (activeTool !== 'select' && selectedItem?.type === 'workflow') {
      setIsDrawing(true);
      const rect = drawingCanvasRef.current?.getBoundingClientRect();
      if (rect) {
        const ctx = drawingCanvasRef.current?.getContext('2d');
        if (ctx) {
          ctx.beginPath();
          ctx.lineCap = 'round';
          ctx.lineJoin = 'round';
          ctx.moveTo((e.clientX - rect.left) / zoom, (e.clientY - rect.top) / zoom);
        }
      }
      return;
    }

    if (e.target === e.currentTarget) {
      setSelectedIds([]);
      setSelectionBox({
        startX: (e.clientX - pan.x) / zoom,
        startY: (e.clientY - pan.y) / zoom,
        x: (e.clientX - pan.x) / zoom,
        y: (e.clientY - pan.y) / zoom,
        w: 0,
        h: 0
      });
      setContextMenu(null);
    }
  };

  const handleMouseMove = (e: React.MouseEvent) => {
    if (isPanning) {
      onPanChange({ x: pan.x + (e.clientX - lastMousePos.x), y: pan.y + (e.clientY - lastMousePos.y) });
      setLastMousePos({ x: e.clientX, y: e.clientY });
      return;
    }

    if (isDrawing && selectedItem?.type === 'workflow') {
      const rect = drawingCanvasRef.current?.getBoundingClientRect();
      if (rect) {
        const ctx = drawingCanvasRef.current?.getContext('2d');
        if (ctx) {
          ctx.strokeStyle = activeTool === 'eraser' ? '#000000' : '#000000';
          ctx.lineWidth = brushSize;
          if (activeTool === 'eraser') ctx.globalCompositeOperation = 'destination-out';
          else ctx.globalCompositeOperation = 'source-over';
          ctx.lineTo((e.clientX - rect.left) / zoom, (e.clientY - rect.top) / zoom);
          ctx.stroke();
        }
      }
      return;
    }

    if (selectionBox) {
      const curX = (e.clientX - pan.x) / zoom;
      const curY = (e.clientY - pan.y) / zoom;
      setSelectionBox({
        ...selectionBox,
        x: Math.min(curX, selectionBox.startX),
        y: Math.min(curY, selectionBox.startY),
        w: Math.abs(curX - selectionBox.startX),
        h: Math.abs(curY - selectionBox.startY)
      });
      return;
    }

    if (resizeState) {
      const item = items.find(i => i.id === resizeState.id);
      if (!item) return;
      const dx = (e.clientX - resizeState.startX) / zoom;
      const dy = (e.clientY - resizeState.startY) / zoom;
      const { direction, startW, startH, startItemX, startItemY } = resizeState;
      let { newW, newH, newX, newY } = { newW: startW, newH: startH, newX: startItemX, newY: startItemY };
      
      if (direction.includes('e')) newW = Math.max(50, startW + dx);
      if (direction.includes('s')) newH = Math.max(50, startH + dy);
      if (direction.includes('w')) { const d = Math.min(startW - 50, dx); newW = startW - d; newX = startItemX + d; }
      if (direction.includes('n')) { const d = Math.min(startH - 50, dy); newH = startH - d; newY = startItemY + d; }
      
      onItemUpdate(resizeState.id, { width: newW, height: newH, x: newX, y: newY });
      return;
    }

    if (dragState) {
      const dx = (e.clientX - lastMousePos.x) / zoom;
      const dy = (e.clientY - lastMousePos.y) / zoom;
      items.filter(i => selectedIds.includes(i.id)).forEach(item => {
        onItemUpdate(item.id, { x: item.x + dx, y: item.y + dy });
      });
      setLastMousePos({ x: e.clientX, y: e.clientY });
    }
  };

  const handleMouseUp = () => {
    if (isDrawing && selectedItem?.type === 'workflow') {
      setIsDrawing(false);
      const data = drawingCanvasRef.current?.toDataURL();
      if (data) onItemUpdate(selectedItem.id, { drawingData: data });
    }
    if (selectionBox) {
      const selected = items.filter(item => (
        item.x < selectionBox.x + selectionBox.w &&
        item.x + item.width > selectionBox.x &&
        item.y < selectionBox.y + selectionBox.h &&
        item.y + item.height > selectionBox.y
      )).map(i => i.id);
      setSelectedIds(selected);
      setSelectionBox(null);
    }
    setIsPanning(false);
    setDragState(null);
    setResizeState(null);
  };

  const startItemDrag = (e: React.MouseEvent, id: string) => {
    if (activeTool !== 'select' || isSpacePressed) return;
    if ((e.target as HTMLElement).tagName === 'TEXTAREA' || (e.target as HTMLElement).tagName === 'INPUT') return;
    if (e.button !== 0) return; 
    
    e.stopPropagation();
    if (!selectedIds.includes(id)) {
      setSelectedIds(e.shiftKey ? [...selectedIds, id] : [id]);
    }
    setDragState({ id, startX: e.clientX, startY: e.clientY });
    setLastMousePos({ x: e.clientX, y: e.clientY });
    setContextMenu(null);
  };

  const handleItemContextMenu = (e: React.MouseEvent, id: string) => {
    e.preventDefault();
    e.stopPropagation();
    if (!selectedIds.includes(id)) {
      setSelectedIds([id]);
    }
    setContextMenu({ x: e.clientX, y: e.clientY, id });
  };

  const handleGenerateFromFramework = async () => {
    if (!selectedItem || selectedItem.type !== 'workflow') return;
    setIsGenerating(true);
    try {
      const canvas = document.createElement('canvas');
      canvas.width = selectedItem.width;
      canvas.height = selectedItem.height;
      const ctx = canvas.getContext('2d');
      if (!ctx) return;
      ctx.fillStyle = '#ffffff';
      ctx.fillRect(0, 0, canvas.width, canvas.height);
      
      const subItems = items.filter(i => 
        i.id !== selectedItem.id &&
        i.x >= selectedItem.x - 200 && i.x + i.width <= selectedItem.x + selectedItem.width + 200 &&
        i.y >= selectedItem.y - 200 && i.y + i.height <= selectedItem.y + selectedItem.height + 200
      ).sort((a, b) => (a.zIndex || 0) - (b.zIndex || 0));
      
      for (const item of subItems) {
        if (item.type === 'image' || item.type === 'workflow') {
          const img = new Image();
          img.crossOrigin = "anonymous";
          img.src = item.content || item.compositeImage || '';
          await new Promise(r => { img.onload = r; img.onerror = r; });
          ctx.drawImage(img, item.x - selectedItem.x, item.y - selectedItem.y, item.width, item.height);
        } else if (item.type === 'text') {
          ctx.font = 'bold 24px sans-serif';
          ctx.fillStyle = '#000000';
          ctx.fillText(item.content, item.x - selectedItem.x + 10, item.y - selectedItem.y + 30);
        }
      }
      
      if (selectedItem.drawingData) {
        const drawImg = new Image();
        drawImg.src = selectedItem.drawingData;
        await new Promise(r => drawImg.onload = r);
        ctx.drawImage(drawImg, 0, 0, canvas.width, canvas.height);
      }
      
      const snapshot = canvas.toDataURL('image/png');
      onItemUpdate(selectedItem.id, { status: 'loading' });
      
      const result = await generateWorkflowImage(frameworkPrompt, snapshot);
      
      const newId = Math.random().toString(36).substr(2, 9);
      const newItem: CanvasItem = {
        id: newId,
        type: 'image',
        content: result,
        x: selectedItem.x + selectedItem.width + 100, 
        y: selectedItem.y,
        width: selectedItem.width,
        height: selectedItem.height,
        status: 'completed',
        label: frameworkPrompt || '视觉逻辑生成',
        zIndex: 500,
        layers: []
      };
      
      onItemAdd(newItem);
      onItemUpdate(selectedItem.id, { status: 'completed' });
      setFrameworkPrompt('');
      setSelectedIds([newId]); 
    } catch (error) {
      console.error(error);
      onItemUpdate(selectedItem.id, { status: 'error' });
    } finally {
      setIsGenerating(false);
    }
  };

  const adjustZIndex = (id: string, action: 'front' | 'back') => {
    const currentZ = items.map(i => i.zIndex || 0);
    const maxZ = Math.max(0, ...currentZ);
    const minZ = Math.min(0, ...currentZ);
    onItemUpdate(id, { zIndex: action === 'front' ? maxZ + 1 : minZ - 1 });
    setContextMenu(null);
  };

  const renderResizeHandle = (id: string, dir: ResizeDirection) => {
    const style: React.CSSProperties = {
      position: 'absolute', width: '16px', height: '16px', backgroundColor: 'white',
      border: '2px solid #6366f1', borderRadius: '4px', zIndex: 1000000,
      cursor: `${dir}-resize`,
      ...(dir.includes('n') ? { top: -8 } : dir.includes('s') ? { bottom: -8 } : { top: '50%', marginTop: -8 }),
      ...(dir.includes('w') ? { left: -8 } : dir.includes('e') ? { right: -8 } : { left: '50%', marginLeft: -8 }),
    };
    return (
      <div key={dir} style={style} onMouseDown={(e) => {
        e.stopPropagation();
        const item = items.find(i => i.id === id);
        if (item) setResizeState({ 
          id, direction: dir, startX: e.clientX, startY: e.clientY, 
          startW: item.width, startH: item.height, startItemX: item.x, startItemY: item.y 
        });
      }} />
    );
  };

  const contextMenuItem = items.find(i => i.id === contextMenu?.id);

  return (
    <div 
      ref={containerRef}
      className={`flex-1 relative overflow-hidden canvas-grid bg-[#fcfcfc] select-none ${isSpacePressed ? 'cursor-grab active:cursor-grabbing' : 'cursor-default'}`}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
    >
      <div 
        className="absolute transition-transform duration-75 will-change-transform" 
        style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`, transformOrigin: '0 0' }}
      >
        {/* 底层内容 */}
        {items.map((item) => (
          <div
            key={item.id}
            onMouseDown={(e) => startItemDrag(e, item.id)}
            onContextMenu={(e) => handleItemContextMenu(e, item.id)}
            className={`absolute rounded-[16px] transition-shadow duration-300 ${
              selectedIds.includes(item.id) ? 'ring-2 ring-indigo-500 shadow-2xl' : 'shadow-lg'
            } ${item.type === 'workflow' ? 'border-2 border-dashed border-indigo-200' : ''}`}
            style={{ 
              left: item.x, top: item.y, width: item.width, height: item.height, 
              zIndex: item.zIndex || 0,
              backgroundColor: item.type === 'text' ? 'transparent' : '#fff'
            }}
          >
            <div className="w-full h-full relative rounded-[14px] overflow-hidden">
              {item.status === 'loading' && (
                <div className="absolute inset-0 bg-white/80 backdrop-blur-sm z-50 flex items-center justify-center">
                  <Loader2 className="animate-spin text-indigo-500" />
                </div>
              )}
              
              {item.type === 'text' ? (
                <textarea
                  value={item.content}
                  onChange={(e) => onItemUpdate(item.id, { content: e.target.value })}
                  className="w-full h-full p-3 bg-transparent outline-none resize-none font-bold text-gray-800"
                />
              ) : item.type === 'workflow' ? (
                <div className="w-full h-full bg-white/30" />
              ) : (
                <img src={item.content} className="w-full h-full object-contain pointer-events-none" />
              )}
            </div>

            {selectedIds.length === 1 && selectedIds[0] === item.id && (
              (['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as ResizeDirection[]).map(dir => renderResizeHandle(item.id, dir))
            )}
          </div>
        ))}

        {/* 顶层画笔层 (HYPER-TOP LAYER)：确保在线条在所有图层之上 */}
        <div className="absolute inset-0 pointer-events-none" style={{ zIndex: 999999 }}>
          {items.map((item) => {
            if (item.type !== 'workflow') return null;
            return (
              <div 
                key={`top-draw-${item.id}`}
                className="absolute"
                style={{ left: item.x, top: item.y, width: item.width, height: item.height }}
              >
                {(selectedIds.includes(item.id) && activeTool !== 'select') ? (
                  <canvas
                    ref={drawingCanvasRef}
                    width={item.width}
                    height={item.height}
                    className="absolute inset-0 w-full h-full cursor-crosshair pointer-events-auto"
                  />
                ) : item.drawingData ? (
                  <img src={item.drawingData} className="absolute inset-0 w-full h-full pointer-events-none" />
                ) : null}
              </div>
            );
          })}
        </div>

        {/* 工具栏栏 */}
        {selectedItem?.type === 'workflow' && selectedIds.length === 1 && (
          <div 
            className="absolute z-[2000000] flex flex-col items-center gap-3 animate-in fade-in zoom-in-95 pointer-events-auto"
            style={{ 
              left: selectedItem.x + selectedItem.width / 2, 
              top: selectedItem.y + selectedItem.height + 32,
              transform: 'translateX(-50%)'
            }}
            onMouseDown={(e) => e.stopPropagation()}
          >
            <div className="flex items-center gap-2 p-2 bg-white border border-gray-100 rounded-[20px] shadow-[0_32px_80px_-16px_rgba(0,0,0,0.15)]">
              <div className="flex items-center gap-1">
                <button onClick={() => setActiveTool('select')} className={`p-2.5 rounded-xl transition-all ${activeTool === 'select' ? 'bg-black text-white' : 'hover:bg-gray-100 text-gray-400'}`}><MousePointer2 size={20} /></button>
                <button onClick={() => setActiveTool('brush')} className={`p-2.5 rounded-xl transition-all ${activeTool === 'brush' ? 'bg-black text-white' : 'hover:bg-gray-100 text-gray-400'}`}><Pencil size={20} /></button>
                <button onClick={() => setActiveTool('eraser')} className={`p-2.5 rounded-xl transition-all ${activeTool === 'eraser' ? 'bg-black text-white' : 'hover:bg-gray-100 text-gray-400'}`}><Eraser size={20} /></button>
              </div>
              <div className="w-px h-6 bg-gray-100 mx-1" />
              <div className="relative flex items-center">
                <input 
                  type="text" 
                  value={frameworkPrompt} 
                  onChange={(e) => setFrameworkPrompt(e.target.value)} 
                  placeholder="空提示词将自动推理布局..." 
                  className="w-72 pl-4 pr-12 py-3 bg-gray-50 border border-gray-200 rounded-xl text-sm font-bold outline-none focus:ring-4 focus:ring-indigo-500/10 transition-all" 
                  onKeyDown={(e) => e.key === 'Enter' && handleGenerateFromFramework()} 
                />
                <button 
                  onClick={handleGenerateFromFramework} 
                  disabled={isGenerating} 
                  className="absolute right-1.5 w-9 h-9 bg-black text-white rounded-lg flex items-center justify-center disabled:opacity-30 hover:scale-105 active:scale-95 transition-all shadow-lg"
                >
                  {isGenerating ? <Loader2 size={16} className="animate-spin" /> : <Sparkles size={16} />}
                </button>
              </div>
            </div>
          </div>
        )}
      </div>

      {contextMenu && (
        <div className="fixed z-[3000000] w-64 bg-white/95 backdrop-blur-3xl border border-gray-100 rounded-2xl shadow-2xl p-2.5 flex flex-col gap-1 animate-in zoom-in-95" style={{ left: contextMenu.x, top: contextMenu.y }} onMouseDown={(e) => e.stopPropagation()}>
          {contextMenuItem?.type === 'image' && (
            <button onClick={() => { onAddToChat(contextMenuItem); setContextMenu(null); }} className="flex items-center gap-3 w-full px-3 py-3 hover:bg-indigo-50 rounded-xl text-sm font-bold text-indigo-600 transition-all">
              <MessageSquarePlus size={20} />加入 AI 对话重塑
            </button>
          )}
          <div className="h-px bg-gray-50 my-1 mx-2" />
          <button onClick={() => adjustZIndex(contextMenu.id, 'front')} className="flex items-center justify-between w-full px-3 py-3 hover:bg-gray-50 rounded-xl text-sm font-bold text-gray-700 transition-all">
            <div className="flex items-center gap-3"><ChevronUp size={20} className="text-gray-400" />置于顶层</div>
          </button>
          <button onClick={() => adjustZIndex(contextMenu.id, 'back')} className="flex items-center justify-between w-full px-3 py-3 hover:bg-gray-50 rounded-xl text-sm font-bold text-gray-700 transition-all">
            <div className="flex items-center gap-3"><ChevronDown size={20} className="text-gray-400" />置于底层</div>
          </button>
          <div className="h-px bg-gray-50 my-1 mx-2" />
          <button onClick={() => { onItemDelete(contextMenu.id); setContextMenu(null); }} className="flex items-center gap-3 w-full px-3 py-3 hover:bg-red-50 rounded-xl text-sm font-bold text-red-500 transition-all">
            <Trash2 size={20} />删除选中节点
          </button>
        </div>
      )}

      {selectionBox && (
        <div className="absolute border-2 border-indigo-500 bg-indigo-500/10 pointer-events-none z-[1000000]" style={{ left: selectionBox.x * zoom + pan.x, top: selectionBox.y * zoom + pan.y, width: selectionBox.w * zoom, height: selectionBox.h * zoom }} />
      )}
    </div>
  );
};

export default Canvas;
