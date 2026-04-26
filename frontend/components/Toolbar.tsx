
import React, { useRef } from 'react';
import { MousePointer2, Hand, Image as ImageIcon, Type, ZoomIn, ZoomOut, Maximize, Undo2, Redo2, LayoutGrid } from 'lucide-react';
import type { CanvasTool } from '../types';

interface ToolbarProps {
  zoom: number;
  onZoomChange: (newZoom: number) => void;
  onResetView: () => void;
  onAddImage: (file: File) => void;
  onAddText: () => void;
  activeTool: CanvasTool;
  onToolChange: (tool: CanvasTool) => void;
  canUndo: boolean;
  canRedo: boolean;
  onUndo: () => void;
  onRedo: () => void;
  canAutoArrangeImages: boolean;
  onAutoArrangeImages: () => void;
}

const Toolbar: React.FC<ToolbarProps> = ({ zoom, onZoomChange, onResetView, onAddImage, onAddText, activeTool, onToolChange, canUndo, canRedo, onUndo, onRedo, canAutoArrangeImages, onAutoArrangeImages }) => {
  const fileInputRef = useRef<HTMLInputElement>(null);

  const handleImageClick = () => {
    fileInputRef.current?.click();
  };

  const handleFileChange = (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    if (file) {
      onAddImage(file);
      e.target.value = ''; // Reset for next selection
    }
  };

  const iconButtonClass = 'group relative flex h-9 w-9 items-center justify-center rounded-xl text-gray-700 transition-colors hover:bg-gray-100 disabled:cursor-not-allowed disabled:opacity-30';
  const activeIconButtonClass = 'bg-zinc-900 text-white hover:bg-zinc-900';
  const renderTooltip = (label: string) => (
    <span className="pointer-events-none absolute bottom-full left-1/2 mb-2 -translate-x-1/2 whitespace-nowrap rounded-lg border border-zinc-800 bg-zinc-900 px-2 py-1 text-[11px] font-bold text-white opacity-0 transition-opacity group-hover:opacity-100">
      {label}
    </span>
  );

  return (
    <div className="fixed bottom-1.5 left-1/2 z-[3000000] flex -translate-x-1/2 items-center gap-1 rounded-2xl border border-gray-200 bg-white/95 p-1.5 backdrop-blur-xl">
      <div className="flex items-center gap-1 border-r border-gray-100 pr-1.5">
        <button
          onClick={onUndo}
          disabled={!canUndo}
          className={iconButtonClass}
          aria-label="撤回"
        >
          <Undo2 size={18} />
          {renderTooltip('撤回')}
        </button>
        <button
          onClick={onRedo}
          disabled={!canRedo}
          className={iconButtonClass}
          aria-label="重做"
        >
          <Redo2 size={18} />
          {renderTooltip('重做')}
        </button>
        <div className="h-6 w-px bg-gray-100" />
        <button
          onClick={() => onToolChange('pan')}
          className={`${iconButtonClass} ${activeTool === 'pan' ? activeIconButtonClass : ''}`}
          aria-label="移动画布"
        >
          <Hand size={18} />
          {renderTooltip('移动画布')}
        </button>
        <button
          onClick={() => onToolChange('select')}
          className={`${iconButtonClass} ${activeTool === 'select' ? activeIconButtonClass : ''}`}
          aria-label="选择/拖拽"
        >
          <MousePointer2 size={18} />
          {renderTooltip('选择')}
        </button>
      </div>

      <div className="flex items-center gap-1 border-r border-gray-100 pr-1.5">
        <button 
          onClick={handleImageClick}
          className={iconButtonClass}
          aria-label="上传图片组件"
        >
          <ImageIcon size={18} />
          {renderTooltip('上传图片')}
          <input 
            type="file" 
            ref={fileInputRef} 
            className="hidden" 
            accept="image/*" 
            onChange={handleFileChange} 
          />
        </button>

        <button 
          onClick={onAddText}
          className={`${iconButtonClass} ${activeTool === 'text' ? activeIconButtonClass : ''}`}
          aria-label="添加文本组件"
        >
          <Type size={18} />
          {renderTooltip('添加文本')}
        </button>

        <button
          onClick={onAutoArrangeImages}
          disabled={!canAutoArrangeImages}
          className={iconButtonClass}
          aria-label="排列图片"
        >
          <LayoutGrid size={18} />
          {renderTooltip('排列')}
        </button>
      </div>

      <div className="flex items-center gap-1">
        <button onClick={() => onZoomChange(Math.max(0.1, zoom - 0.1))} className={iconButtonClass} aria-label="缩小">
          <ZoomOut size={18} />
          {renderTooltip('缩小')}
        </button>
        <button onClick={() => onZoomChange(Math.min(5, zoom + 0.1))} className={iconButtonClass} aria-label="放大">
          <ZoomIn size={18} />
          {renderTooltip('放大')}
        </button>
        <button onClick={onResetView} className={iconButtonClass} aria-label="重置视图">
          <Maximize size={18} />
          {renderTooltip('重置视图')}
        </button>
      </div>
    </div>
  );
};

export default Toolbar;
