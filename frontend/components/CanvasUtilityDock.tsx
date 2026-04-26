import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Check, Image as ImageIcon, Images, LocateFixed, Map as MapIcon, Palette, Search, X } from 'lucide-react';
import type { CanvasItem } from '../types';
import { getImagePreviewFitStyle, getThumbnailImageSrc } from '../services/imageSources';

type UtilityPanel = 'color' | 'files' | 'minimap' | null;

interface CanvasUtilityDockProps {
  items: CanvasItem[];
  selectedIds: string[];
  pan: { x: number; y: number };
  zoom: number;
  showSidebar: boolean;
  backgroundColor: string;
  onBackgroundColorChange: (color: string) => void;
  onNavigateToItem: (item: CanvasItem) => void;
  onPanChange: (pan: { x: number; y: number }) => void;
}

const SIDEBAR_WIDTH = 384;
const MINIMAP_WIDTH = 280;
const MINIMAP_HEIGHT = 180;
const MINIMAP_PADDING = 14;

const CANVAS_COLOR_PRESETS = [
  { label: '默认白', value: '#fcfcfc' },
  { label: '暖米色', value: '#f8f2e8' },
  { label: '雾灰色', value: '#f3f4f6' },
  { label: '浅蓝灰', value: '#eef4ff' },
  { label: '暗夜灰', value: '#111827' },
  { label: '纯黑', value: '#020617' }
];

const getItemTitle = (item: CanvasItem) => (
  item.label || item.originalPrompt || item.prompt || item.id
);

const getStatusText = (item: CanvasItem) => {
  if (item.status === 'loading') return '生成中';
  if (item.status === 'error') return '失败';
  if (item.type === 'image' && item.content) return item.parentId ? '编辑结果' : '成品图';
  return '画布节点';
};

const clampNumber = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

const CanvasUtilityDock: React.FC<CanvasUtilityDockProps> = ({
  items,
  selectedIds,
  pan,
  zoom,
  showSidebar,
  backgroundColor,
  onBackgroundColorChange,
  onNavigateToItem,
  onPanChange
}) => {
  const [activePanel, setActivePanel] = useState<UtilityPanel>(null);
  const [fileSearchQuery, setFileSearchQuery] = useState('');
  const [viewportSize, setViewportSize] = useState({
    width: window.innerWidth,
    height: window.innerHeight
  });
  const [isDraggingMinimap, setIsDraggingMinimap] = useState(false);
  const minimapRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    const updateViewportSize = () => {
      setViewportSize({
        width: window.innerWidth,
        height: window.innerHeight
      });
    };

    window.addEventListener('resize', updateViewportSize);
    return () => window.removeEventListener('resize', updateViewportSize);
  }, []);

  const imageItems = useMemo(() => (
    items
      .filter(item => item.type === 'image')
      .slice()
      .reverse()
  ), [items]);

  const filteredImageItems = useMemo(() => {
    const normalizedQuery = fileSearchQuery.trim().toLowerCase();
    if (!normalizedQuery) return imageItems;

    return imageItems.filter(item => {
      const title = getItemTitle(item).toLowerCase();
      return title.includes(normalizedQuery) || item.id.toLowerCase().includes(normalizedQuery);
    });
  }, [fileSearchQuery, imageItems]);

  const availableCanvasWidth = Math.max(320, viewportSize.width - (showSidebar ? SIDEBAR_WIDTH : 0));
  const viewportWorldRect = useMemo(() => ({
    x: -pan.x / zoom,
    y: -pan.y / zoom,
    width: availableCanvasWidth / zoom,
    height: viewportSize.height / zoom
  }), [availableCanvasWidth, pan.x, pan.y, viewportSize.height, zoom]);

  const minimapLayout = useMemo(() => {
    const drawableItems = items.filter(item => item.type !== 'research');
    const rects = [
      viewportWorldRect,
      ...drawableItems.map(item => ({
        x: item.x,
        y: item.y,
        width: item.width,
        height: item.height
      }))
    ];

    const minX = Math.min(...rects.map(rect => rect.x));
    const minY = Math.min(...rects.map(rect => rect.y));
    const maxX = Math.max(...rects.map(rect => rect.x + rect.width));
    const maxY = Math.max(...rects.map(rect => rect.y + rect.height));
    const boundsWidth = Math.max(1, maxX - minX);
    const boundsHeight = Math.max(1, maxY - minY);
    const scale = Math.min(
      (MINIMAP_WIDTH - MINIMAP_PADDING * 2) / boundsWidth,
      (MINIMAP_HEIGHT - MINIMAP_PADDING * 2) / boundsHeight
    );
    const contentWidth = boundsWidth * scale;
    const contentHeight = boundsHeight * scale;
    const offsetX = (MINIMAP_WIDTH - contentWidth) / 2;
    const offsetY = (MINIMAP_HEIGHT - contentHeight) / 2;

    const toMinimapRect = (rect: Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>) => ({
      left: offsetX + (rect.x - minX) * scale,
      top: offsetY + (rect.y - minY) * scale,
      width: Math.max(2, rect.width * scale),
      height: Math.max(2, rect.height * scale)
    });

    const pointToWorld = (clientX: number, clientY: number) => {
      const minimapElement = minimapRef.current;
      if (!minimapElement) return null;

      const rect = minimapElement.getBoundingClientRect();
      const localX = clampNumber(clientX - rect.left, 0, rect.width);
      const localY = clampNumber(clientY - rect.top, 0, rect.height);
      return {
        x: minX + (localX - offsetX) / scale,
        y: minY + (localY - offsetY) / scale
      };
    };

    return {
      items: drawableItems,
      viewport: toMinimapRect(viewportWorldRect),
      toMinimapRect,
      pointToWorld
    };
  }, [items, viewportWorldRect]);

  const centerViewportAt = (clientX: number, clientY: number) => {
    const worldPoint = minimapLayout.pointToWorld(clientX, clientY);
    if (!worldPoint) return;

    onPanChange({
      x: availableCanvasWidth / 2 - worldPoint.x * zoom,
      y: viewportSize.height / 2 - worldPoint.y * zoom
    });
  };

  const togglePanel = (panel: Exclude<UtilityPanel, null>) => {
    setActivePanel(currentPanel => currentPanel === panel ? null : panel);
  };

  const renderDockButton = (
    panel: Exclude<UtilityPanel, null>,
    label: string,
    icon: React.ReactNode
  ) => (
    <button
      type="button"
      onClick={() => togglePanel(panel)}
      title={label}
      className={`flex h-11 w-11 items-center justify-center rounded-2xl transition-all ${
        activePanel === panel
          ? 'bg-black text-white shadow-lg shadow-black/15'
          : 'bg-white text-gray-500 shadow-sm ring-1 ring-gray-100 hover:-translate-y-0.5 hover:text-gray-900 hover:shadow-lg'
      }`}
    >
      {icon}
    </button>
  );

  const renderColorPanel = () => (
    <div className="w-[320px] rounded-[28px] border border-gray-100 bg-white/95 p-4 shadow-[0_30px_90px_-28px_rgba(15,23,42,0.35)] backdrop-blur-3xl">
      <div className="mb-4 flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-black text-gray-900">画布颜色</div>
          <div className="mt-1 text-[11px] font-bold text-gray-400">只改变无限画布底色，不影响图片内容</div>
        </div>
        <button type="button" onClick={() => setActivePanel(null)} className="rounded-xl p-2 text-gray-300 transition-colors hover:bg-gray-50 hover:text-gray-700">
          <X size={15} />
        </button>
      </div>
      <div className="grid grid-cols-3 gap-2">
        {CANVAS_COLOR_PRESETS.map(color => (
          <button
            key={color.value}
            type="button"
            onClick={() => onBackgroundColorChange(color.value)}
            className={`group rounded-2xl border p-2 text-left transition-all hover:-translate-y-0.5 hover:shadow-lg ${
              backgroundColor.toLowerCase() === color.value.toLowerCase()
                ? 'border-black bg-gray-50'
                : 'border-gray-100 bg-white'
            }`}
          >
            <span className="mb-2 flex h-12 rounded-xl border border-black/5" style={{ backgroundColor: color.value }} />
            <span className="flex items-center justify-between gap-2 text-xs font-black text-gray-700">
              {color.label}
              {backgroundColor.toLowerCase() === color.value.toLowerCase() && <Check size={14} />}
            </span>
          </button>
        ))}
      </div>
      <label className="mt-4 flex items-center justify-between gap-3 rounded-2xl border border-gray-100 bg-gray-50 px-3 py-2">
        <span className="text-xs font-black text-gray-500">自定义颜色</span>
        <input
          type="color"
          value={backgroundColor}
          onChange={event => onBackgroundColorChange(event.target.value)}
          className="h-9 w-12 cursor-pointer rounded-xl border-0 bg-transparent p-0"
          aria-label="自定义画布颜色"
        />
      </label>
    </div>
  );

  const renderFilesPanel = () => (
    <div className="w-[380px] overflow-hidden rounded-[28px] border border-gray-100 bg-white/95 shadow-[0_30px_90px_-28px_rgba(15,23,42,0.35)] backdrop-blur-3xl">
      <div className="border-b border-gray-100 p-4">
        <div className="mb-3 flex items-start justify-between gap-3">
          <div>
            <div className="text-sm font-black text-gray-900">已生成文件</div>
            <div className="mt-1 text-[11px] font-bold text-gray-400">查看、搜索并定位所有图片结果</div>
          </div>
          <button type="button" onClick={() => setActivePanel(null)} className="rounded-xl p-2 text-gray-300 transition-colors hover:bg-gray-50 hover:text-gray-700">
            <X size={15} />
          </button>
        </div>
        <div className="flex h-10 items-center gap-2 rounded-2xl bg-gray-50 px-3 text-gray-500 ring-1 ring-gray-100 focus-within:bg-white focus-within:ring-indigo-100">
          <Search size={15} className="shrink-0 text-gray-400" />
          <input
            value={fileSearchQuery}
            onChange={event => setFileSearchQuery(event.target.value)}
            placeholder="按名称、提示词或 ID 搜索"
            className="min-w-0 flex-1 bg-transparent text-sm font-semibold text-gray-800 outline-none placeholder:text-gray-400"
          />
        </div>
      </div>
      <div className="max-h-[420px] overflow-y-auto p-2 scrollbar-thin scrollbar-thumb-gray-200 scrollbar-track-transparent">
        {filteredImageItems.length > 0 ? (
          filteredImageItems.map(item => {
            const previewStyle = getImagePreviewFitStyle(item, 72, 54);
            const isSelected = selectedIds.includes(item.id);

            return (
              <button
                key={item.id}
                type="button"
                onClick={() => onNavigateToItem(item)}
                className={`group flex w-full items-center gap-3 rounded-2xl p-2 text-left transition-all hover:bg-indigo-50 ${
                  isSelected ? 'bg-indigo-50 ring-1 ring-indigo-100' : 'bg-white'
                }`}
              >
                <span className="flex h-16 w-20 shrink-0 items-center justify-center rounded-2xl bg-gray-100">
                  {item.content ? (
                    <span className="overflow-hidden rounded-xl bg-white shadow-sm ring-1 ring-gray-100" style={previewStyle}>
                      <img src={getThumbnailImageSrc(item)} className="h-full w-full object-contain" />
                    </span>
                  ) : (
                    <ImageIcon size={18} className="text-gray-300" />
                  )}
                </span>
                <span className="min-w-0 flex-1">
                  <span className="block truncate text-sm font-black text-gray-800">{getItemTitle(item)}</span>
                  <span className="mt-1 block truncate font-mono text-[10px] font-black text-gray-300">@{item.id}</span>
                  <span className="mt-1 flex flex-wrap items-center gap-1.5 text-[10px] font-black text-gray-400">
                    <span className="rounded-full bg-gray-100 px-2 py-0.5">{Math.round(item.width)}×{Math.round(item.height)}</span>
                    <span className={`rounded-full px-2 py-0.5 ${
                      item.status === 'error'
                        ? 'bg-red-50 text-red-500'
                        : item.status === 'loading'
                          ? 'bg-amber-50 text-amber-600'
                          : 'bg-emerald-50 text-emerald-600'
                    }`}>{getStatusText(item)}</span>
                  </span>
                </span>
                <LocateFixed size={16} className="shrink-0 text-gray-300 transition-colors group-hover:text-indigo-500" />
              </button>
            );
          })
        ) : (
          <div className="flex h-44 flex-col items-center justify-center rounded-3xl border border-dashed border-gray-200 bg-gray-50 text-center">
            <Images size={22} className="mb-2 text-gray-300" />
            <div className="text-sm font-black text-gray-500">暂无图片文件</div>
            <div className="mt-1 text-xs font-medium text-gray-400">生成或上传图片后会出现在这里</div>
          </div>
        )}
      </div>
    </div>
  );

  const renderMinimapPanel = () => (
    <div className="w-[320px] rounded-[28px] border border-gray-100 bg-white/95 p-4 shadow-[0_30px_90px_-28px_rgba(15,23,42,0.35)] backdrop-blur-3xl">
      <div className="mb-3 flex items-start justify-between gap-3">
        <div>
          <div className="text-sm font-black text-gray-900">小地图</div>
          <div className="mt-1 text-[11px] font-bold text-gray-400">查看画布全局分布，点击或拖动快速移动视野</div>
        </div>
        <button type="button" onClick={() => setActivePanel(null)} className="rounded-xl p-2 text-gray-300 transition-colors hover:bg-gray-50 hover:text-gray-700">
          <X size={15} />
        </button>
      </div>
      <div
        ref={minimapRef}
        className="relative cursor-crosshair overflow-hidden rounded-3xl border border-gray-100 bg-gray-50"
        style={{
          width: MINIMAP_WIDTH,
          height: MINIMAP_HEIGHT,
          backgroundColor
        }}
        onPointerDown={event => {
          setIsDraggingMinimap(true);
          event.currentTarget.setPointerCapture(event.pointerId);
          centerViewportAt(event.clientX, event.clientY);
        }}
        onPointerMove={event => {
          if (isDraggingMinimap) centerViewportAt(event.clientX, event.clientY);
        }}
        onPointerUp={event => {
          setIsDraggingMinimap(false);
          event.currentTarget.releasePointerCapture(event.pointerId);
        }}
        onPointerCancel={() => setIsDraggingMinimap(false)}
      >
        {minimapLayout.items.map(item => {
          const rect = minimapLayout.toMinimapRect(item);
          const isSelected = selectedIds.includes(item.id);
          return (
            <div
              key={item.id}
              className={`absolute rounded-[3px] ${
                item.type === 'image'
                  ? item.status === 'error'
                    ? 'bg-red-400'
                    : 'bg-indigo-500'
                  : item.type === 'text'
                    ? 'bg-amber-400'
                    : 'bg-emerald-400'
              } ${isSelected ? 'ring-2 ring-black ring-offset-1 ring-offset-white' : 'ring-1 ring-white/70'}`}
              style={rect}
            />
          );
        })}
        <div
          className="absolute rounded-lg border-2 border-black bg-black/5 shadow-[0_0_0_1px_rgba(255,255,255,0.8)]"
          style={minimapLayout.viewport}
        />
      </div>
      <div className="mt-3 flex items-center justify-between text-[10px] font-black text-gray-400">
        <span>{items.length} 个节点</span>
        <span>{Math.round(zoom * 100)}% 缩放</span>
      </div>
    </div>
  );

  return (
    <div className="fixed bottom-8 left-6 z-[3000000] flex flex-col items-start gap-3">
      {activePanel && (
        <div className="animate-in fade-in slide-in-from-bottom-2">
          {activePanel === 'color' && renderColorPanel()}
          {activePanel === 'files' && renderFilesPanel()}
          {activePanel === 'minimap' && renderMinimapPanel()}
        </div>
      )}
      <div className="flex items-center gap-2 rounded-[24px] border border-gray-100 bg-white/75 p-1.5 shadow-[0_22px_70px_-32px_rgba(15,23,42,0.45)] backdrop-blur-3xl">
        {renderDockButton('color', '设置画布颜色', <Palette size={19} />)}
        {renderDockButton('files', '已生成的文件列表', <Images size={19} />)}
        {renderDockButton('minimap', '小地图', <MapIcon size={19} />)}
      </div>
    </div>
  );
};

export default CanvasUtilityDock;
