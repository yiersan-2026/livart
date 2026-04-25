
import React, { useState, useRef, useEffect, useCallback } from 'react';
import type { CanvasItem, ImageAspectRatio } from '../types';
import { 
  Loader2, Trash2, Type, 
  Sparkles, ChevronUp, ChevronDown, 
  MousePointer2, Eraser, 
  MessageSquarePlus, Pencil,
  Copy, Layers
} from 'lucide-react';
import { editImage, generateWorkflowImage } from '../services/gemini';
import { optimizePrompt } from '../services/promptOptimizer';
import {
  IMAGE_ASPECT_RATIO_OPTIONS,
  centerFrameOnRect,
  getAspectRatioFrame,
  getImageFrameFromSource
} from '../services/imageSizing';

interface CanvasProps {
  items: CanvasItem[];
  zoom: number;
  onZoomChange: (newZoom: number, anchorPoint?: { x: number; y: number }) => void;
  pan: { x: number; y: number };
  onPanChange: (pan: { x: number; y: number }) => void;
  onItemUpdate: (id: string, updates: Partial<CanvasItem>) => void;
  onItemDelete: (id: string) => void;
  onItemDeleteMultiple: (ids: string[]) => void;
  onItemAdd: (item: CanvasItem) => void; 
  onAddTextAt: (x: number, y: number) => void;
  onAddImageAt: (file: File, x: number, y: number) => void;
  onAddToChat: (item: CanvasItem) => void;
  selectedIds: string[];
  setSelectedIds: (ids: string[]) => void;
}

type ResizeDirection = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw';

const getCanvasDimension = (value: number) => Math.max(1, Math.round(value));

const loadImageElement = (src: string) => new Promise<HTMLImageElement>((resolve, reject) => {
  const image = new Image();
  image.onload = () => resolve(image);
  image.onerror = () => reject(new Error('图片加载失败，无法生成局部重绘蒙版'));
  image.src = src;
});

const getImageDimensions = async (src: string, fallbackWidth: number, fallbackHeight: number) => {
  try {
    const image = await loadImageElement(src);
    return {
      width: getCanvasDimension(image.naturalWidth || fallbackWidth),
      height: getCanvasDimension(image.naturalHeight || fallbackHeight)
    };
  } catch {
    return {
      width: getCanvasDimension(fallbackWidth),
      height: getCanvasDimension(fallbackHeight)
    };
  }
};

const createTransparentEditMask = async (
  paintMaskDataUrl: string,
  imageDataUrl: string,
  displayWidth: number,
  displayHeight: number
) => {
  const displayCanvasWidth = getCanvasDimension(displayWidth);
  const displayCanvasHeight = getCanvasDimension(displayHeight);
  const imageDimensions = await getImageDimensions(imageDataUrl, displayCanvasWidth, displayCanvasHeight);
  const paintImage = await loadImageElement(paintMaskDataUrl);

  const displayPaintCanvas = document.createElement('canvas');
  displayPaintCanvas.width = displayCanvasWidth;
  displayPaintCanvas.height = displayCanvasHeight;
  const displayPaintContext = displayPaintCanvas.getContext('2d');
  if (!displayPaintContext) throw new Error('无法创建局部重绘蒙版');
  displayPaintContext.clearRect(0, 0, displayCanvasWidth, displayCanvasHeight);
  displayPaintContext.drawImage(paintImage, 0, 0, displayCanvasWidth, displayCanvasHeight);

  const normalizedPaintCanvas = document.createElement('canvas');
  normalizedPaintCanvas.width = imageDimensions.width;
  normalizedPaintCanvas.height = imageDimensions.height;
  const normalizedPaintContext = normalizedPaintCanvas.getContext('2d');
  if (!normalizedPaintContext) throw new Error('无法创建局部重绘蒙版');

  const containScale = Math.min(
    displayCanvasWidth / imageDimensions.width,
    displayCanvasHeight / imageDimensions.height
  );
  const renderedWidth = imageDimensions.width * containScale;
  const renderedHeight = imageDimensions.height * containScale;
  const renderedX = (displayCanvasWidth - renderedWidth) / 2;
  const renderedY = (displayCanvasHeight - renderedHeight) / 2;

  normalizedPaintContext.clearRect(0, 0, imageDimensions.width, imageDimensions.height);
  normalizedPaintContext.drawImage(
    displayPaintCanvas,
    renderedX,
    renderedY,
    renderedWidth,
    renderedHeight,
    0,
    0,
    imageDimensions.width,
    imageDimensions.height
  );

  const paintPixels = normalizedPaintContext.getImageData(0, 0, imageDimensions.width, imageDimensions.height);
  const apiMaskCanvas = document.createElement('canvas');
  apiMaskCanvas.width = imageDimensions.width;
  apiMaskCanvas.height = imageDimensions.height;
  const apiMaskContext = apiMaskCanvas.getContext('2d');
  if (!apiMaskContext) throw new Error('无法创建局部重绘蒙版');

  const apiMaskPixels = apiMaskContext.createImageData(imageDimensions.width, imageDimensions.height);
  let hasPaintedArea = false;

  for (let index = 0; index < paintPixels.data.length; index += 4) {
    const isPainted = paintPixels.data[index + 3] > 12;
    if (isPainted) hasPaintedArea = true;
    apiMaskPixels.data[index] = 0;
    apiMaskPixels.data[index + 1] = 0;
    apiMaskPixels.data[index + 2] = 0;
    apiMaskPixels.data[index + 3] = isPainted ? 0 : 255;
  }

  if (!hasPaintedArea) return null;

  apiMaskContext.putImageData(apiMaskPixels, 0, 0);
  return apiMaskCanvas.toDataURL('image/png');
};

const isEditableTarget = (target: EventTarget | null) => {
  if (!(target instanceof HTMLElement)) return false;
  return (
    ['INPUT', 'TEXTAREA', 'SELECT'].includes(target.tagName) ||
    !!target.closest('[contenteditable="true"]')
  );
};

const Canvas: React.FC<CanvasProps> = ({ 
  items, zoom, onZoomChange, pan, onPanChange, onItemUpdate, onItemDelete, onItemDeleteMultiple, onItemAdd, onAddTextAt, onAddImageAt, onAddToChat, selectedIds, setSelectedIds
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
  const [isDraggingImageFile, setIsDraggingImageFile] = useState(false);

  // 绘图工具状态
  const [activeTool, setActiveTool] = useState<'select' | 'brush' | 'eraser'>('select');
  const [brushSize, setBrushSize] = useState(8);
  const [isDrawing, setIsDrawing] = useState(false);
  const drawingCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const [frameworkPrompt, setFrameworkPrompt] = useState('');
  const [isGenerating, setIsGenerating] = useState(false);
  const [inlineEditPrompts, setInlineEditPrompts] = useState<Record<string, string>>({});
  const [inlineEditErrors, setInlineEditErrors] = useState<Record<string, string>>({});
  const [inlineEditingIds, setInlineEditingIds] = useState<Set<string>>(() => new Set());
  const [inlinePromptOptimizingIds, setInlinePromptOptimizingIds] = useState<Set<string>>(() => new Set());
  const [inlineEditAspectRatio, setInlineEditAspectRatio] = useState<ImageAspectRatio>('auto');
  const [localRedrawItemId, setLocalRedrawItemId] = useState<string | null>(null);
  const inlineEditingIdsRef = useRef<Set<string>>(new Set());
  const inlinePromptOptimizingIdsRef = useRef<Set<string>>(new Set());
  const maskCanvasRef = useRef<HTMLCanvasElement | null>(null);

  const containerRef = useRef<HTMLDivElement>(null);
  const selectedItem = items.find(i => selectedIds.length === 1 && i.id === selectedIds[0]);
  const selectedItemIsInlineEditing = selectedItem ? inlineEditingIds.has(selectedItem.id) : false;
  const selectedItemIsInlinePromptOptimizing = selectedItem ? inlinePromptOptimizingIds.has(selectedItem.id) : false;
  const selectedItemIsLocalRedraw = selectedItem?.type === 'image' && selectedItem.id === localRedrawItemId;
  const selectedInlineEditError = selectedItem ? inlineEditErrors[selectedItem.id] : '';
  const inlineEditPrompt = selectedItem?.type === 'image' ? inlineEditPrompts[selectedItem.id] || '' : '';

  // 全局右键菜单状态
  const [contextMenu, setContextMenu] = useState<{ x: number, y: number, id: string } | null>(null);

  // 修复假死：当选中项改变时，确保重置绘图状态，防止 Ref 冲突或状态死循环
  useEffect(() => {
    const canUseDrawingTool = selectedItem?.type === 'workflow' || selectedItemIsLocalRedraw;
    if (!canUseDrawingTool || activeTool === 'select') {
      setIsDrawing(false);
      if (activeTool !== 'select') setActiveTool('select');
    }
  }, [activeTool, selectedIds, localRedrawItemId, selectedItem?.type, selectedItemIsLocalRedraw]);

  useEffect(() => {
    if (!selectedItemIsLocalRedraw || !selectedItem) return;

    const canvas = maskCanvasRef.current;
    if (!canvas) return;

    const width = getCanvasDimension(selectedItem.width);
    const height = getCanvasDimension(selectedItem.height);
    canvas.width = width;
    canvas.height = height;

    const context = canvas.getContext('2d');
    if (!context) return;

    context.clearRect(0, 0, width, height);
    if (!selectedItem.maskData) return;

    let cancelled = false;
    loadImageElement(selectedItem.maskData)
      .then((image) => {
        if (cancelled) return;
        context.clearRect(0, 0, width, height);
        context.drawImage(image, 0, 0, width, height);
      })
      .catch(() => {
        if (!cancelled) context.clearRect(0, 0, width, height);
      });

    return () => {
      cancelled = true;
    };
  }, [selectedItemIsLocalRedraw, selectedItem?.id, selectedItem?.maskData, selectedItem?.width, selectedItem?.height]);

  const setInlineEditingForItem = (id: string, isEditing: boolean) => {
    const nextEditingIds = new Set(inlineEditingIdsRef.current);
    if (isEditing) {
      nextEditingIds.add(id);
    } else {
      nextEditingIds.delete(id);
    }
    inlineEditingIdsRef.current = nextEditingIds;
    setInlineEditingIds(nextEditingIds);
  };

  const setInlinePromptOptimizingForItem = (id: string, isOptimizing: boolean) => {
    const nextOptimizingIds = new Set(inlinePromptOptimizingIdsRef.current);
    if (isOptimizing) {
      nextOptimizingIds.add(id);
    } else {
      nextOptimizingIds.delete(id);
    }
    inlinePromptOptimizingIdsRef.current = nextOptimizingIds;
    setInlinePromptOptimizingIds(nextOptimizingIds);
  };

  const clearInlineEditError = (id: string) => {
    setInlineEditErrors(prev => {
      if (!prev[id]) return prev;
      const nextErrors = { ...prev };
      delete nextErrors[id];
      return nextErrors;
    });
  };

  const setInlineEditPromptForItem = (id: string, prompt: string) => {
    setInlineEditPrompts(prev => ({ ...prev, [id]: prompt }));
  };

  const getCurrentMaskDataForItem = (id: string, fallbackMaskData?: string) => {
    if (localRedrawItemId === id && maskCanvasRef.current) {
      return maskCanvasRef.current.toDataURL('image/png');
    }
    return fallbackMaskData;
  };

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (isEditableTarget(e.target)) return;

      if (e.code === 'Space') {
        setIsSpacePressed(true);
        if (e.target === document.body) e.preventDefault();
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && selectedIds.length > 0) {
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
    const handleWheel = (event: WheelEvent) => {
      if (isEditableTarget(event.target)) return;

      event.preventDefault();

      if (event.ctrlKey || event.metaKey) {
        const normalizedDeltaY = event.deltaMode === 1 ? event.deltaY * 16 : event.deltaY;
        const nextZoom = zoom * Math.exp(-normalizedDeltaY * 0.002);
        onZoomChange(nextZoom, { x: event.clientX, y: event.clientY });
        return;
      }

      const panDeltaX = event.shiftKey && !event.deltaX ? event.deltaY : event.deltaX;
      onPanChange({
        x: pan.x - panDeltaX,
        y: pan.y - event.deltaY
      });
    };

    const container = containerRef.current;
    container?.addEventListener('wheel', handleWheel, { passive: false });
    return () => container?.removeEventListener('wheel', handleWheel);
  }, [zoom, pan, onZoomChange, onPanChange]);

  const handleMouseDown = (e: React.MouseEvent) => {
    if (e.button !== 0) return;
    if (isSpacePressed) {
      setIsPanning(true);
      setLastMousePos({ x: e.clientX, y: e.clientY });
      return;
    }

    // 图片局部重绘蒙版：置顶层画布捕获
    if (activeTool !== 'select' && selectedItemIsLocalRedraw) {
      setIsDrawing(true);
      const rect = maskCanvasRef.current?.getBoundingClientRect();
      if (rect) {
        const ctx = maskCanvasRef.current?.getContext('2d');
        if (ctx) {
          const x = (e.clientX - rect.left) / zoom;
          const y = (e.clientY - rect.top) / zoom;
          ctx.beginPath();
          ctx.lineCap = 'round';
          ctx.lineJoin = 'round';
          ctx.lineWidth = brushSize;
          ctx.strokeStyle = 'rgba(99, 102, 241, 0.55)';
          ctx.globalCompositeOperation = activeTool === 'eraser' ? 'destination-out' : 'source-over';
          ctx.moveTo(x, y);
          ctx.lineTo(x + 0.01, y + 0.01);
          ctx.stroke();
        }
      }
      return;
    }

    // 画笔逻辑：置顶层画布捕获
    if (activeTool !== 'select' && selectedItem?.type === 'workflow') {
      setIsDrawing(true);
      const rect = drawingCanvasRef.current?.getBoundingClientRect();
      if (rect) {
        const ctx = drawingCanvasRef.current?.getContext('2d');
        if (ctx) {
          const x = (e.clientX - rect.left) / zoom;
          const y = (e.clientY - rect.top) / zoom;
          ctx.beginPath();
          ctx.lineCap = 'round';
          ctx.lineJoin = 'round';
          ctx.strokeStyle = '#000000';
          ctx.lineWidth = brushSize;
          ctx.globalCompositeOperation = activeTool === 'eraser' ? 'destination-out' : 'source-over';
          ctx.moveTo(x, y);
          ctx.lineTo(x + 0.01, y + 0.01);
          ctx.stroke();
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

    if (isDrawing && selectedItemIsLocalRedraw) {
      const rect = maskCanvasRef.current?.getBoundingClientRect();
      if (rect) {
        const ctx = maskCanvasRef.current?.getContext('2d');
        if (ctx) {
          ctx.strokeStyle = 'rgba(99, 102, 241, 0.55)';
          ctx.lineWidth = brushSize;
          ctx.globalCompositeOperation = activeTool === 'eraser' ? 'destination-out' : 'source-over';
          ctx.lineTo((e.clientX - rect.left) / zoom, (e.clientY - rect.top) / zoom);
          ctx.stroke();
        }
      }
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
    if (isDrawing && selectedItemIsLocalRedraw) {
      setIsDrawing(false);
      const data = maskCanvasRef.current?.toDataURL('image/png');
      if (data && selectedItem) onItemUpdate(selectedItem.id, { maskData: data });
    } else if (isDrawing && selectedItem?.type === 'workflow') {
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

  const getDroppedImageFiles = (fileList: FileList) => {
    return Array.from(fileList).filter(file => file.type.startsWith('image/'));
  };

  const handleDragOver = (event: React.DragEvent) => {
    if (!Array.from(event.dataTransfer.types).includes('Files')) return;
    event.preventDefault();
    event.dataTransfer.dropEffect = 'copy';
    setIsDraggingImageFile(true);
  };

  const handleDragLeave = (event: React.DragEvent) => {
    const nextTarget = event.relatedTarget;
    if (nextTarget instanceof Node && event.currentTarget.contains(nextTarget)) return;
    setIsDraggingImageFile(false);
  };

  const handleDrop = (event: React.DragEvent) => {
    if (!Array.from(event.dataTransfer.types).includes('Files')) return;
    event.preventDefault();
    setIsDraggingImageFile(false);

    const imageFiles = getDroppedImageFiles(event.dataTransfer.files);
    if (imageFiles.length === 0) return;

    const dropX = (event.clientX - pan.x) / zoom;
    const dropY = (event.clientY - pan.y) / zoom;

    imageFiles.forEach((file, index) => {
      onAddImageAt(file, dropX + index * 28, dropY + index * 28);
    });
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
      const workflowResultFrame = await getImageFrameFromSource(
        result,
        selectedItem.width,
        selectedItem.height,
        Math.max(selectedItem.width, selectedItem.height)
      );
      const newItem: CanvasItem = {
        id: newId,
        type: 'image',
        content: result,
        x: selectedItem.x + selectedItem.width + 100, 
        y: selectedItem.y,
        width: workflowResultFrame.width,
        height: workflowResultFrame.height,
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

  const handleInlineImageRedraw = async (event: React.FormEvent) => {
    event.preventDefault();
    const prompt = inlineEditPrompt.trim();
    if (!prompt || !selectedItem || selectedItem.type !== 'image' || inlineEditingIdsRef.current.has(selectedItem.id) || inlinePromptOptimizingIdsRef.current.has(selectedItem.id)) return;

    const targetItem = selectedItem;
    const useLocalMask = localRedrawItemId === targetItem.id;
    let resultItemId: string | null = null;
    setInlineEditingForItem(targetItem.id, true);
    setInlinePromptOptimizingForItem(targetItem.id, true);
    clearInlineEditError(targetItem.id);

    try {
      const currentMaskData = useLocalMask
        ? getCurrentMaskDataForItem(targetItem.id, targetItem.maskData)
        : undefined;

      if (useLocalMask && !currentMaskData) {
        throw new Error('请先用画笔涂抹需要局部重绘的区域');
      }

      const promptToOptimize = useLocalMask
        ? `${prompt}。只修改用户用蒙版涂抹的局部区域，未被蒙版覆盖的区域必须保持原图不变。`
        : prompt;
      const optimizedPrompt = await optimizePrompt(promptToOptimize, 'image-to-image');
      setInlineEditPromptForItem(targetItem.id, optimizedPrompt);
      setInlinePromptOptimizingForItem(targetItem.id, false);

      let maskDataUrl: string | null | undefined;
      if (useLocalMask) {
        maskDataUrl = await createTransparentEditMask(currentMaskData!, targetItem.content, targetItem.width, targetItem.height);
        if (!maskDataUrl) {
          throw new Error('请先用画笔涂抹需要局部重绘的区域');
        }
      }

      const siblingCount = items.filter(item => item.parentId === targetItem.id).length;
      const nextZIndex = Math.max(0, ...items.map(item => item.zIndex || 0)) + 1;
      const newId = Math.random().toString(36).substr(2, 9);
      const resultMaxLongSide = Math.max(targetItem.width, targetItem.height);
      const resultFrame = getAspectRatioFrame(
        inlineEditAspectRatio,
        targetItem.width,
        targetItem.height,
        resultMaxLongSide
      );
      const resultItem: CanvasItem = {
        id: newId,
        type: 'image',
        content: '',
        x: targetItem.x + targetItem.width + 120 + siblingCount * 36,
        y: targetItem.y + siblingCount * 36,
        width: resultFrame.width,
        height: resultFrame.height,
        status: 'loading',
        label: 'AI 重绘中...',
        zIndex: nextZIndex,
        parentId: targetItem.id,
        prompt: optimizedPrompt,
        layers: []
      };

      onItemAdd(resultItem);
      resultItemId = newId;
      setSelectedIds([newId]);

      const result = await editImage(optimizedPrompt, targetItem.content, maskDataUrl || undefined, inlineEditAspectRatio);
      const finalFrame = await getImageFrameFromSource(
        result,
        resultFrame.width,
        resultFrame.height,
        resultMaxLongSide
      );
      onItemUpdate(newId, {
        ...centerFrameOnRect(resultItem, finalFrame),
        content: result,
        status: 'completed',
        label: optimizedPrompt.substring(0, 16) + (optimizedPrompt.length > 16 ? '...' : '')
      });
      if (useLocalMask) {
        onItemUpdate(targetItem.id, { maskData: undefined });
        setLocalRedrawItemId(prev => prev === targetItem.id ? null : prev);
        setActiveTool('select');
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '未知错误';
      console.error(error);
      if (resultItemId) {
        onItemUpdate(resultItemId, {
          status: 'error',
          label: 'AI 重绘失败'
        });
      }
      setInlineEditErrors(prev => ({ ...prev, [targetItem.id]: message }));
    } finally {
      setInlinePromptOptimizingForItem(targetItem.id, false);
      setInlineEditingForItem(targetItem.id, false);
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
  const parentConnections = items.flatMap((item) => {
    if (!item.parentId) return [];
    const parent = items.find(candidate => candidate.id === item.parentId);
    if (!parent) return [];

    const parentCenterX = parent.x + parent.width / 2;
    const itemCenterX = item.x + item.width / 2;
    const connectsToRight = itemCenterX >= parentCenterX;
    const direction = connectsToRight ? 1 : -1;
    const startX = connectsToRight ? parent.x + parent.width : parent.x;
    const endX = connectsToRight ? item.x : item.x + item.width;
    const startY = parent.y + parent.height / 2;
    const endY = item.y + item.height / 2;
    const bend = Math.max(80, Math.abs(endX - startX) * 0.45);

    return [{
      id: `${parent.id}-${item.id}`,
      path: `M ${startX} ${startY} C ${startX + direction * bend} ${startY}, ${endX - direction * bend} ${endY}, ${endX} ${endY}`
    }];
  });

  return (
    <div 
      ref={containerRef}
      className={`flex-1 relative overflow-hidden canvas-grid bg-[#fcfcfc] select-none ${isSpacePressed ? 'cursor-grab active:cursor-grabbing' : 'cursor-default'}`}
      onMouseDown={handleMouseDown}
      onMouseMove={handleMouseMove}
      onMouseUp={handleMouseUp}
      onDragOver={handleDragOver}
      onDragLeave={handleDragLeave}
      onDrop={handleDrop}
    >
      {isDraggingImageFile && (
        <div className="absolute inset-4 z-[4000000] pointer-events-none rounded-[32px] border-2 border-dashed border-indigo-400 bg-indigo-500/10 flex items-center justify-center">
          <div className="px-5 py-3 rounded-2xl bg-white/95 shadow-2xl border border-indigo-100 text-sm font-black text-indigo-600">
            松开鼠标，将图片添加到画布
          </div>
        </div>
      )}

      <div 
        className="absolute transition-transform duration-75 will-change-transform" 
        style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`, transformOrigin: '0 0' }}
      >
        {parentConnections.length > 0 && (
          <svg
            className="absolute overflow-visible pointer-events-none"
            style={{ left: 0, top: 0, width: 1, height: 1, zIndex: 0 }}
          >
            <defs>
              <marker id="redraw-connection-arrow" viewBox="0 0 10 10" refX="8" refY="5" markerWidth="7" markerHeight="7" orient="auto-start-reverse">
                <path d="M 0 0 L 10 5 L 0 10 z" fill="#6366f1" />
              </marker>
            </defs>
            {parentConnections.map(connection => (
              <path
                key={connection.id}
                d={connection.path}
                fill="none"
                stroke="#6366f1"
                strokeWidth={2}
                strokeDasharray="8 8"
                strokeLinecap="round"
                markerEnd="url(#redraw-connection-arrow)"
                opacity={0.75}
              />
            ))}
          </svg>
        )}

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
          {items.map((item) => {
            if (item.type !== 'image') return null;
            const isActiveMaskItem = selectedItemIsLocalRedraw && selectedItem?.id === item.id;
            if (!isActiveMaskItem && !item.maskData) return null;
            return (
              <div
                key={`mask-draw-${item.id}`}
                className="absolute"
                style={{ left: item.x, top: item.y, width: item.width, height: item.height }}
              >
                {isActiveMaskItem ? (
                  <canvas
                    ref={maskCanvasRef}
                    width={getCanvasDimension(item.width)}
                    height={getCanvasDimension(item.height)}
                    className={`absolute inset-0 w-full h-full rounded-[14px] ${
                      activeTool === 'select' ? 'pointer-events-none' : 'cursor-crosshair pointer-events-auto'
                    }`}
                  />
                ) : item.maskData ? (
                  <img src={item.maskData} className="absolute inset-0 w-full h-full rounded-[14px] pointer-events-none" />
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

        {selectedItem?.type === 'image' && selectedIds.length === 1 && (
          <form
            onSubmit={handleInlineImageRedraw}
            className="absolute z-[2000000] flex flex-col items-center gap-2 animate-in fade-in zoom-in-95 pointer-events-auto"
            style={{
              left: selectedItem.x + selectedItem.width / 2,
              top: selectedItem.y + selectedItem.height + 28,
              transform: 'translateX(-50%)'
            }}
            onMouseDown={(event) => event.stopPropagation()}
          >
            <div className="flex w-[520px] max-w-[80vw] items-center gap-2 rounded-2xl border border-gray-100 bg-white/95 p-1.5 shadow-[0_20px_60px_-24px_rgba(0,0,0,0.22)]">
              <label className="flex shrink-0 items-center gap-1.5 rounded-xl bg-gray-50 px-2 py-1.5 text-[11px] font-black text-gray-500">
                画幅
                <select
                  value={inlineEditAspectRatio}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  onChange={(event) => setInlineEditAspectRatio(event.target.value as ImageAspectRatio)}
                  className="bg-transparent text-[11px] font-black text-gray-700 outline-none disabled:opacity-40"
                >
                  {IMAGE_ASPECT_RATIO_OPTIONS.map((option) => (
                    <option key={option.value} value={option.value}>
                      {option.label}
                    </option>
                  ))}
                </select>
              </label>
              <button
                type="button"
                onClick={() => {
                  setLocalRedrawItemId(prev => {
                    const nextId = prev === selectedItem.id ? null : selectedItem.id;
                    setActiveTool(nextId ? 'brush' : 'select');
                    return nextId;
                  });
                }}
                disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                className={`flex shrink-0 items-center gap-1.5 rounded-xl px-3 py-2 text-xs font-black transition-all ${
                  selectedItemIsLocalRedraw
                    ? 'bg-indigo-600 text-white shadow-lg shadow-indigo-500/20'
                    : 'bg-gray-50 text-gray-500 hover:bg-indigo-50 hover:text-indigo-600'
                } disabled:opacity-40`}
              >
                <Layers size={15} />局部
              </button>
              {selectedItemIsLocalRedraw && (
                <>
                  <button type="button" onClick={() => setActiveTool('select')} className={`rounded-xl p-2 transition-all ${activeTool === 'select' ? 'bg-black text-white' : 'text-gray-400 hover:bg-gray-100'}`} title="选择/移动">
                    <MousePointer2 size={16} />
                  </button>
                  <button type="button" onClick={() => setActiveTool('brush')} className={`rounded-xl p-2 transition-all ${activeTool === 'brush' ? 'bg-black text-white' : 'text-gray-400 hover:bg-gray-100'}`} title="涂抹重绘区域">
                    <Pencil size={16} />
                  </button>
                  <button type="button" onClick={() => setActiveTool('eraser')} className={`rounded-xl p-2 transition-all ${activeTool === 'eraser' ? 'bg-black text-white' : 'text-gray-400 hover:bg-gray-100'}`} title="擦除蒙版">
                    <Eraser size={16} />
                  </button>
                  <input
                    type="range"
                    min={4}
                    max={64}
                    value={brushSize}
                    onChange={(event) => setBrushSize(Number(event.target.value))}
                    className="h-1 flex-1 accent-indigo-600"
                    title="画笔大小"
                  />
                  <button
                    type="button"
                    onClick={() => onItemUpdate(selectedItem.id, { maskData: undefined })}
                    className="rounded-xl px-2.5 py-2 text-xs font-black text-gray-400 transition-all hover:bg-red-50 hover:text-red-500"
                  >
                    清除
                  </button>
                </>
              )}
            </div>
            <div className="relative flex items-end w-[520px] max-w-[80vw] p-1.5 bg-white border border-gray-100 rounded-2xl shadow-[0_32px_80px_-16px_rgba(0,0,0,0.18)]">
              <textarea
                value={inlineEditPrompt}
                onChange={(event) => setInlineEditPromptForItem(selectedItem.id, event.target.value)}
                onKeyDown={(event) => {
                  event.stopPropagation();
                  if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
                    event.preventDefault();
                    event.currentTarget.form?.requestSubmit();
                  }
                }}
                disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                rows={3}
                placeholder={selectedItemIsLocalRedraw ? '先涂抹区域，再输入局部重绘指令' : '输入重绘指令，例如：换成赛博朋克风格'}
                className="scrollbar-hide w-full min-h-[72px] max-h-28 resize-none overflow-y-auto overflow-x-hidden pl-4 pr-12 py-3 bg-gray-50 border border-gray-100 rounded-xl text-sm leading-5 font-bold outline-none focus:ring-4 focus:ring-indigo-500/10 disabled:opacity-60 transition-all"
              />
              <button
                type="submit"
                disabled={!inlineEditPrompt.trim() || selectedItemIsInlineEditing || selectedItem.status === 'loading' || selectedItemIsInlinePromptOptimizing}
                className={`absolute right-3 w-9 h-9 text-white rounded-lg flex items-center justify-center hover:scale-105 active:scale-95 transition-all shadow-lg ${
                  selectedItemIsInlinePromptOptimizing
                    ? 'prompt-optimizing-button'
                    : 'bg-black disabled:opacity-30'
                }`}
                title={selectedItemIsLocalRedraw ? '局部重绘当前图片' : '重绘当前图片'}
              >
                {selectedItemIsInlineEditing || selectedItem.status === 'loading' || selectedItemIsInlinePromptOptimizing ? <Loader2 size={16} className="animate-spin" /> : <Sparkles size={16} />}
              </button>
            </div>
            {selectedInlineEditError && (
              <div className="max-w-[520px] rounded-xl bg-red-50 border border-red-100 px-3 py-2 text-[11px] font-bold text-red-500 shadow-lg">
                {selectedInlineEditError}
              </div>
            )}
          </form>
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
