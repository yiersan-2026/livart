
import React, { useState, useRef, useEffect, useMemo } from 'react';
import type { CanvasItem, CanvasTool, ImageAspectRatio } from '../types';
import { 
  Loader2, Trash2, Type, 
  Sparkles, ChevronUp, ChevronDown, 
  MousePointer2, Eraser, 
  MessageSquarePlus, Pencil,
  Copy, Layers, Scissors, Check, X,
  ImagePlus, Palette, Maximize2, Wand2,
  Download, Send
} from 'lucide-react';
import { canUseImageJobs, editImage, generateWorkflowImage, type ImageGenerationResult, submitImageEditJob, waitForImageJob } from '../services/gemini';
import {
  centerFrameOnRect,
  getAspectRatioFrame,
  getImageFrameFromSource,
  inferAspectRatioFromDimensions
} from '../services/imageSizing';
import { getCanvasImageSrc, getOriginalImageSrc } from '../services/imageSources';
import {
  buildImageReferenceRoleContext,
  buildReferencedImageEditPrompt,
  normalizeOptimizedPromptImageReferences,
  resolveEditReferencesWithAi,
  resolveMentionedImageReferences
} from '../services/imageReferences';
import { ensureCanvasImageAsset, getCanvasItemAssetId } from '../services/canvasPersistence';

interface CanvasProps {
  items: CanvasItem[];
  zoom: number;
  onZoomChange: (newZoom: number, anchorPoint?: { x: number; y: number }) => void;
  pan: { x: number; y: number };
  onPanChange: (pan: { x: number; y: number }) => void;
  backgroundColor: string;
  onItemUpdate: (id: string, updates: Partial<CanvasItem>) => void;
  onItemDelete: (id: string) => void;
  onItemDeleteMultiple: (ids: string[]) => void;
  onItemAdd: (item: CanvasItem) => void; 
  onAddTextAt: (x: number, y: number) => void;
  onAddImageAt: (file: File, x: number, y: number) => void;
  onAddToChat: (item: CanvasItem) => void;
  onImagePromptRequest: (item: CanvasItem, prompt?: string, mode?: 'local-redraw' | 'remover') => void;
  onBeforeCanvasMutation: () => void;
  canvasTool: CanvasTool;
  onCanvasToolChange: (tool: CanvasTool) => void;
  selectedIds: string[];
  setSelectedIds: (ids: string[]) => void;
}

type ResizeDirection = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw';
type MaskPoint = { x: number; y: number };
type CropRect = { x: number; y: number; width: number; height: number };
type CropDragState = {
  mode: 'move' | ResizeDirection;
  startX: number;
  startY: number;
  startRect: CropRect;
  itemWidth: number;
  itemHeight: number;
};

const REMOVER_PROMPT = '把圈起来的地方删除掉。';

const getCanvasDimension = (value: number) => Math.max(1, Math.round(value));

const MIN_CROP_SIZE = 48;
const INLINE_IMAGE_EDITOR_GAP = 16;
const CANVAS_OVERLAY_MARGIN = 16;
const DEFAULT_INLINE_IMAGE_EDITOR_SIZE = { width: 760, height: 52 };
const DERIVED_IMAGE_GAP = 32;

const clampValue = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

const canvasRectsOverlap = (
  left: Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>,
  right: Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>,
  padding = DERIVED_IMAGE_GAP / 2
) => (
  left.x < right.x + right.width + padding &&
  left.x + left.width + padding > right.x &&
  left.y < right.y + right.height + padding &&
  left.y + left.height + padding > right.y
);

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
    if (!relevantItems.some(item => canvasRectsOverlap(candidate, item))) {
      return { x: desiredX, y: desiredY };
    }
    desiredX += width + DERIVED_IMAGE_GAP;
  }

  return { x: desiredX, y: desiredY };
};

const clampCropRect = (rect: CropRect, itemWidth: number, itemHeight: number): CropRect => {
  const width = Math.min(Math.max(MIN_CROP_SIZE, rect.width), itemWidth);
  const height = Math.min(Math.max(MIN_CROP_SIZE, rect.height), itemHeight);
  const x = Math.min(Math.max(0, rect.x), Math.max(0, itemWidth - width));
  const y = Math.min(Math.max(0, rect.y), Math.max(0, itemHeight - height));
  return { x, y, width, height };
};

const createInitialCropRect = (item: CanvasItem): CropRect => {
  const width = Math.max(MIN_CROP_SIZE, item.width * 0.8);
  const height = Math.max(MIN_CROP_SIZE, item.height * 0.8);
  return clampCropRect({
    x: (item.width - width) / 2,
    y: (item.height - height) / 2,
    width,
    height
  }, item.width, item.height);
};

const resizeCropRect = (
  startRect: CropRect,
  direction: ResizeDirection,
  dx: number,
  dy: number,
  itemWidth: number,
  itemHeight: number
) => {
  let nextX = startRect.x;
  let nextY = startRect.y;
  let nextWidth = startRect.width;
  let nextHeight = startRect.height;

  if (direction.includes('e')) {
    nextWidth = startRect.width + dx;
  }
  if (direction.includes('s')) {
    nextHeight = startRect.height + dy;
  }
  if (direction.includes('w')) {
    nextWidth = startRect.width - dx;
    nextX = startRect.x + dx;
  }
  if (direction.includes('n')) {
    nextHeight = startRect.height - dy;
    nextY = startRect.y + dy;
  }

  if (nextWidth < MIN_CROP_SIZE) {
    if (direction.includes('w')) nextX -= MIN_CROP_SIZE - nextWidth;
    nextWidth = MIN_CROP_SIZE;
  }
  if (nextHeight < MIN_CROP_SIZE) {
    if (direction.includes('n')) nextY -= MIN_CROP_SIZE - nextHeight;
    nextHeight = MIN_CROP_SIZE;
  }

  return clampCropRect({ x: nextX, y: nextY, width: nextWidth, height: nextHeight }, itemWidth, itemHeight);
};

const loadImageElement = (src: string) => new Promise<HTMLImageElement>((resolve, reject) => {
  const image = new Image();
  image.onload = () => resolve(image);
  image.onerror = () => reject(new Error('图片加载失败，无法处理图片'));
  image.src = src;
});

const createCroppedImageDataUrl = async (item: CanvasItem, cropRect: CropRect) => {
  const source = getOriginalImageSrc(item) || getCanvasImageSrc(item);
  if (!source) throw new Error('图片缺少可裁剪内容');

  const image = await loadImageElement(source);
  const naturalWidth = getCanvasDimension(image.naturalWidth || item.width);
  const naturalHeight = getCanvasDimension(image.naturalHeight || item.height);
  const displayWidth = getCanvasDimension(item.width);
  const displayHeight = getCanvasDimension(item.height);
  const containScale = Math.min(displayWidth / naturalWidth, displayHeight / naturalHeight);
  const renderedWidth = naturalWidth * containScale;
  const renderedHeight = naturalHeight * containScale;
  const renderedX = (displayWidth - renderedWidth) / 2;
  const renderedY = (displayHeight - renderedHeight) / 2;

  const cropLeft = Math.max(cropRect.x, renderedX);
  const cropTop = Math.max(cropRect.y, renderedY);
  const cropRight = Math.min(cropRect.x + cropRect.width, renderedX + renderedWidth);
  const cropBottom = Math.min(cropRect.y + cropRect.height, renderedY + renderedHeight);
  const cropWidth = cropRight - cropLeft;
  const cropHeight = cropBottom - cropTop;

  if (cropWidth < 2 || cropHeight < 2) {
    throw new Error('裁剪框没有覆盖到图片内容');
  }

  const sourceX = (cropLeft - renderedX) / containScale;
  const sourceY = (cropTop - renderedY) / containScale;
  const sourceWidth = cropWidth / containScale;
  const sourceHeight = cropHeight / containScale;
  const outputCanvas = document.createElement('canvas');
  outputCanvas.width = getCanvasDimension(sourceWidth);
  outputCanvas.height = getCanvasDimension(sourceHeight);
  const context = outputCanvas.getContext('2d');
  if (!context) throw new Error('无法创建裁剪画布');

  context.drawImage(
    image,
    sourceX,
    sourceY,
    sourceWidth,
    sourceHeight,
    0,
    0,
    outputCanvas.width,
    outputCanvas.height
  );

  return {
    dataUrl: outputCanvas.toDataURL('image/png'),
    frame: {
      width: Math.max(MIN_CROP_SIZE, cropWidth),
      height: Math.max(MIN_CROP_SIZE, cropHeight)
    }
  };
};

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

const dilatePixels = (sourcePixels: Uint8Array, width: number, height: number, radius: number) => {
  if (radius <= 0) return sourcePixels;

  const dilatedPixels = new Uint8Array(sourcePixels);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const index = y * width + x;
      if (!sourcePixels[index]) continue;

      for (let dy = -radius; dy <= radius; dy += 1) {
        const nextY = y + dy;
        if (nextY < 0 || nextY >= height) continue;

        for (let dx = -radius; dx <= radius; dx += 1) {
          const nextX = x + dx;
          if (nextX < 0 || nextX >= width) continue;
          if (dx * dx + dy * dy > radius * radius) continue;
          dilatedPixels[nextY * width + nextX] = 1;
        }
      }
    }
  }

  return dilatedPixels;
};

const getPolygonArea = (points: MaskPoint[]) => {
  let area = 0;
  for (let index = 0; index < points.length; index += 1) {
    const current = points[index];
    const next = points[(index + 1) % points.length];
    area += current.x * next.y - next.x * current.y;
  }
  return Math.abs(area) / 2;
};

const fillRemoverLassoSelection = (canvas: HTMLCanvasElement | null, points: MaskPoint[], brushSize: number) => {
  if (!canvas || points.length < 3) return;

  const area = getPolygonArea(points);
  if (area < Math.max(64, brushSize * brushSize * 1.5)) return;

  const context = canvas.getContext('2d');
  if (!context) return;

  context.save();
  context.globalCompositeOperation = 'source-over';
  context.lineCap = 'round';
  context.lineJoin = 'round';
  context.lineWidth = brushSize;
  context.strokeStyle = 'rgba(239, 68, 68, 0.7)';
  context.fillStyle = 'rgba(239, 68, 68, 0.28)';
  context.beginPath();
  context.moveTo(points[0].x, points[0].y);
  for (const point of points.slice(1)) {
    context.lineTo(point.x, point.y);
  }
  context.closePath();
  context.fill();
  context.stroke();
  context.restore();
};

const buildEditableMaskFromPaintPixels = (
  paintPixels: ImageData,
  width: number,
  height: number,
  options: { outlineDilationRadius?: number; editableDilationRadius?: number } = {}
) => {
  const totalPixels = width * height;
  const paintedPixels = new Uint8Array(totalPixels);
  const blockedPixels = new Uint8Array(totalPixels);
  const outlineDilationRadius = options.outlineDilationRadius ?? 2;
  const editableDilationRadius = options.editableDilationRadius ?? 0;
  let hasPaintedArea = false;

  for (let index = 0; index < totalPixels; index += 1) {
    const alpha = paintPixels.data[index * 4 + 3];
    if (alpha > 12) {
      paintedPixels[index] = 1;
      blockedPixels[index] = 1;
      hasPaintedArea = true;
    }
  }

  if (!hasPaintedArea) {
    return { editablePixels: paintedPixels, hasPaintedArea: false };
  }

  const dilationRadius = outlineDilationRadius;
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const index = y * width + x;
      if (!paintedPixels[index]) continue;

      for (let dy = -dilationRadius; dy <= dilationRadius; dy += 1) {
        const nextY = y + dy;
        if (nextY < 0 || nextY >= height) continue;

        for (let dx = -dilationRadius; dx <= dilationRadius; dx += 1) {
          const nextX = x + dx;
          if (nextX < 0 || nextX >= width) continue;
          blockedPixels[nextY * width + nextX] = 1;
        }
      }
    }
  }

  const outsidePixels = new Uint8Array(totalPixels);
  const queue = new Int32Array(totalPixels);
  let queueStart = 0;
  let queueEnd = 0;

  const enqueueOutside = (index: number) => {
    if (index < 0 || index >= totalPixels || blockedPixels[index] || outsidePixels[index]) return;
    outsidePixels[index] = 1;
    queue[queueEnd] = index;
    queueEnd += 1;
  };

  for (let x = 0; x < width; x += 1) {
    enqueueOutside(x);
    enqueueOutside((height - 1) * width + x);
  }
  for (let y = 0; y < height; y += 1) {
    enqueueOutside(y * width);
    enqueueOutside(y * width + width - 1);
  }

  while (queueStart < queueEnd) {
    const index = queue[queueStart];
    queueStart += 1;
    const x = index % width;
    const y = Math.floor(index / width);

    if (x > 0) enqueueOutside(index - 1);
    if (x + 1 < width) enqueueOutside(index + 1);
    if (y > 0) enqueueOutside(index - width);
    if (y + 1 < height) enqueueOutside(index + width);
  }

  const editablePixels = new Uint8Array(totalPixels);
  for (let index = 0; index < totalPixels; index += 1) {
    editablePixels[index] = paintedPixels[index] || (!blockedPixels[index] && !outsidePixels[index]) ? 1 : 0;
  }

  return {
    editablePixels: dilatePixels(editablePixels, width, height, editableDilationRadius),
    hasPaintedArea: true
  };
};

const createTransparentEditMask = async (
  paintMaskDataUrl: string,
  imageDataUrl: string,
  displayWidth: number,
  displayHeight: number,
  options: { outlineDilationRadius?: number; editableDilationRadius?: number } = {}
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
  const { editablePixels, hasPaintedArea } = buildEditableMaskFromPaintPixels(
    paintPixels,
    imageDimensions.width,
    imageDimensions.height,
    options
  );
  const apiMaskCanvas = document.createElement('canvas');
  apiMaskCanvas.width = imageDimensions.width;
  apiMaskCanvas.height = imageDimensions.height;
  const apiMaskContext = apiMaskCanvas.getContext('2d');
  if (!apiMaskContext) throw new Error('无法创建局部重绘蒙版');

  const apiMaskPixels = apiMaskContext.createImageData(imageDimensions.width, imageDimensions.height);

  for (let pixelIndex = 0; pixelIndex < editablePixels.length; pixelIndex += 1) {
    const outputIndex = pixelIndex * 4;
    const isEditable = editablePixels[pixelIndex] === 1;
    apiMaskPixels.data[outputIndex] = 0;
    apiMaskPixels.data[outputIndex + 1] = 0;
    apiMaskPixels.data[outputIndex + 2] = 0;
    apiMaskPixels.data[outputIndex + 3] = isEditable ? 0 : 255;
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

const sanitizeDownloadFilename = (value: string) => {
  return value
    .trim()
    .replace(/[\\/:*?"<>|]+/g, '-')
    .replace(/\s+/g, ' ')
    .replace(/\.+$/g, '')
    .slice(0, 80) || 'image';
};

const getImageFileExtension = (source: string, mimeType = '') => {
  const normalizedMimeType = mimeType.toLowerCase();
  if (normalizedMimeType.includes('jpeg') || normalizedMimeType.includes('jpg')) return '.jpg';
  if (normalizedMimeType.includes('webp')) return '.webp';
  if (normalizedMimeType.includes('gif')) return '.gif';
  if (normalizedMimeType.includes('svg')) return '.svg';
  if (normalizedMimeType.includes('avif')) return '.avif';
  if (normalizedMimeType.includes('png')) return '.png';

  const cleanSource = source.split('?')[0].split('#')[0];
  const match = cleanSource.match(/\.([a-z0-9]+)$/i);
  const extension = match?.[1]?.toLowerCase();
  if (extension && ['png', 'jpg', 'jpeg', 'webp', 'gif', 'svg', 'avif'].includes(extension)) {
    return `.${extension === 'jpeg' ? 'jpg' : extension}`;
  }

  return '.png';
};

const getImageDataUrlMimeType = (source: string) => {
  const match = source.match(/^data:([^;,]+)[;,]/);
  return match?.[1] || '';
};

const buildImageDownloadFilename = (item: CanvasItem, extension: string) => {
  const filenameBase = sanitizeDownloadFilename(item.label || `image-${item.id}`);
  return `${filenameBase.replace(/\.(png|jpe?g|webp|gif|svg|avif)$/i, '')}${extension}`;
};

const triggerBrowserDownload = (href: string, filename: string) => {
  const link = document.createElement('a');
  link.href = href;
  link.download = filename;
  link.rel = 'noopener';
  document.body.appendChild(link);
  link.click();
  link.remove();
};

const ImageGenerationSkeleton: React.FC<{ hasPreview: boolean }> = ({ hasPreview }) => (
  <div className={`image-generation-skeleton absolute inset-0 h-full w-full overflow-hidden ${hasPreview ? 'bg-white/72 backdrop-blur-[2px]' : 'bg-zinc-100'}`}>
    <div className="absolute inset-0 bg-[linear-gradient(135deg,rgba(244,244,245,0.92)_0%,rgba(228,228,231,0.9)_42%,rgba(250,250,250,0.95)_100%)]" />
    <div className="absolute inset-0 opacity-60 [background-image:radial-gradient(circle_at_22%_18%,rgba(255,255,255,0.95)_0,rgba(255,255,255,0)_28%),radial-gradient(circle_at_78%_72%,rgba(212,212,216,0.9)_0,rgba(212,212,216,0)_34%)]" />
    <div className="absolute inset-0 opacity-40 [background-size:18px_18px] [background-image:linear-gradient(45deg,rgba(255,255,255,0.52)_25%,transparent_25%,transparent_50%,rgba(255,255,255,0.52)_50%,rgba(255,255,255,0.52)_75%,transparent_75%,transparent)]" />
  </div>
);

const Canvas: React.FC<CanvasProps> = ({ 
  items, zoom, onZoomChange, pan, onPanChange, backgroundColor, onItemUpdate, onItemDelete, onItemDeleteMultiple, onItemAdd, onAddTextAt, onAddImageAt, onAddToChat, onImagePromptRequest, onBeforeCanvasMutation, canvasTool, onCanvasToolChange, selectedIds, setSelectedIds
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
  const [inlineEditAspectRatio, setInlineEditAspectRatio] = useState<ImageAspectRatio>('auto');
  const [localRedrawItemId, setLocalRedrawItemId] = useState<string | null>(null);
  const [localRemoverItemId, setLocalRemoverItemId] = useState<string | null>(null);
  const [quickEditItemId, setQuickEditItemId] = useState<string | null>(null);
  const [cropItemId, setCropItemId] = useState<string | null>(null);
  const [cropRect, setCropRect] = useState<CropRect | null>(null);
  const [cropDragState, setCropDragState] = useState<CropDragState | null>(null);
  const [downloadingImageId, setDownloadingImageId] = useState<string | null>(null);
  const inlineEditingIdsRef = useRef<Set<string>>(new Set());
  const maskCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const quickEditInputRef = useRef<HTMLInputElement | null>(null);
  const maskStrokePointsRef = useRef<MaskPoint[]>([]);
  const imageToolbarRef = useRef<HTMLDivElement | null>(null);
  const [canvasViewportSize, setCanvasViewportSize] = useState({ width: 0, height: 0 });
  const [inlineEditorSize, setInlineEditorSize] = useState(DEFAULT_INLINE_IMAGE_EDITOR_SIZE);

  const containerRef = useRef<HTMLDivElement>(null);
  const selectedItem = items.find(i => selectedIds.length === 1 && i.id === selectedIds[0]);
  const isDraggingSelectedImage = !!dragState && selectedItem?.type === 'image' && selectedIds.includes(dragState.id);
  const selectedItemIsInlineEditing = selectedItem ? inlineEditingIds.has(selectedItem.id) : false;
  const selectedItemIsLocalRedraw = selectedItem?.type === 'image' && selectedItem.id === localRedrawItemId;
  const selectedItemIsRemover = selectedItem?.type === 'image' && selectedItem.id === localRemoverItemId;
  const selectedItemCanQuickEdit = selectedItem?.type === 'image' && selectedItem.status === 'completed' && !!selectedItem.content;
  const selectedItemIsQuickEditing = !!selectedItemCanQuickEdit && selectedItem?.id === quickEditItemId;
  const selectedItemIsCrop = selectedItem?.type === 'image' && selectedItem.id === cropItemId;
  const selectedItemHasImageMaskTool = selectedItemIsLocalRedraw || selectedItemIsRemover;
  const activeImageMaskStrokeColor = selectedItemIsRemover ? 'rgba(239, 68, 68, 0.55)' : 'rgba(99, 102, 241, 0.55)';
  const selectedInlineEditError = selectedItem ? inlineEditErrors[selectedItem.id] : '';
  const inlineEditPrompt = selectedItem?.type === 'image' ? inlineEditPrompts[selectedItem.id] || '' : '';
  const hasActiveCanvasGeneration = isGenerating || inlineEditingIds.size > 0;
  const completedImageItems = useMemo(
    () => items.filter(item => item.type === 'image' && item.status === 'completed' && !!item.content),
    [items]
  );
  const inlineReferenceImageItems = useMemo(
    () => selectedItem?.type === 'image'
      ? completedImageItems.filter(item => item.id !== selectedItem.id)
      : [],
    [completedImageItems, selectedItem?.id, selectedItem?.type]
  );
  const imageItemById = useMemo(
    () => new Map(items.filter(item => item.type === 'image').map(item => [item.id, item])),
    [items]
  );
  const selectedDisplayOptimizedPrompt = useMemo(() => {
    if (!selectedItem?.optimizedPrompt || selectedItem.type !== 'image') return selectedItem?.optimizedPrompt || '';
    const parentImage = selectedItem.parentId ? imageItemById.get(selectedItem.parentId) || null : null;
    if (!parentImage) return selectedItem.optimizedPrompt;
    const referenceImages = completedImageItems.filter(item => item.id !== parentImage.id);
    return normalizeOptimizedPromptImageReferences(selectedItem.optimizedPrompt, parentImage, referenceImages, completedImageItems);
  }, [completedImageItems, imageItemById, selectedItem]);
  const selectedImageToolbarPosition = useMemo(() => {
    const viewportWidth = canvasViewportSize.width;
    const viewportHeight = canvasViewportSize.height;

    if (!selectedItem || selectedItem.type !== 'image') {
      return {
        left: 0,
        top: 0,
        transform: 'scale(1)',
        transformOrigin: 'top left'
      };
    }

    const safeZoom = zoom || 1;
    const imageLeft = pan.x + selectedItem.x * safeZoom;
    const imageTop = pan.y + selectedItem.y * safeZoom;
    const imageWidth = selectedItem.width * safeZoom;
    const imageHeight = selectedItem.height * safeZoom;
    const editorWidth = inlineEditorSize.width || DEFAULT_INLINE_IMAGE_EDITOR_SIZE.width;
    const editorHeight = inlineEditorSize.height || DEFAULT_INLINE_IMAGE_EDITOR_SIZE.height;
    let screenLeft = imageLeft + imageWidth / 2 - editorWidth / 2;

    if (viewportWidth > 0) {
      screenLeft = clampValue(
        screenLeft,
        CANVAS_OVERLAY_MARGIN,
        Math.max(CANVAS_OVERLAY_MARGIN, viewportWidth - CANVAS_OVERLAY_MARGIN - editorWidth)
      );
    }

    let screenTop = imageTop - editorHeight - INLINE_IMAGE_EDITOR_GAP;

    if (viewportHeight > 0) {
      screenTop = clampValue(
        screenTop,
        CANVAS_OVERLAY_MARGIN,
        Math.max(CANVAS_OVERLAY_MARGIN, viewportHeight - CANVAS_OVERLAY_MARGIN - editorHeight)
      );
    }

    return {
      left: (screenLeft - pan.x) / safeZoom,
      top: (screenTop - pan.y) / safeZoom,
      transform: `scale(${1 / safeZoom})`,
      transformOrigin: 'top left'
    };
  }, [canvasViewportSize, inlineEditorSize, pan, selectedItem, zoom]);

  // 全局右键菜单状态
  const [contextMenu, setContextMenu] = useState<{ x: number, y: number, id: string } | null>(null);

  useEffect(() => {
    const element = containerRef.current;
    if (!element) return;

    const updateViewportSize = () => {
      setCanvasViewportSize(previousSize => {
        const nextSize = {
          width: element.clientWidth,
          height: element.clientHeight
        };

        return previousSize.width === nextSize.width && previousSize.height === nextSize.height
          ? previousSize
          : nextSize;
      });
    };

    updateViewportSize();
    window.addEventListener('resize', updateViewportSize);

    if (typeof ResizeObserver === 'undefined') {
      return () => window.removeEventListener('resize', updateViewportSize);
    }

    const observer = new ResizeObserver(updateViewportSize);
    observer.observe(element);

    return () => {
      window.removeEventListener('resize', updateViewportSize);
      observer.disconnect();
    };
  }, []);

  useEffect(() => {
    if (!selectedItem || selectedItem.type !== 'image') return;

    const element = imageToolbarRef.current;
    if (!element) return;

    const updateEditorSize = () => {
      const rect = element.getBoundingClientRect();
      const nextSize = {
        width: Math.ceil(Math.max(rect.width, element.scrollWidth || 0) || DEFAULT_INLINE_IMAGE_EDITOR_SIZE.width),
        height: Math.ceil(rect.height || DEFAULT_INLINE_IMAGE_EDITOR_SIZE.height)
      };

      setInlineEditorSize(previousSize => (
        previousSize.width === nextSize.width && previousSize.height === nextSize.height
          ? previousSize
          : nextSize
      ));
    };

    updateEditorSize();

    if (typeof ResizeObserver === 'undefined') return;

    const observer = new ResizeObserver(updateEditorSize);
    observer.observe(element);

    return () => observer.disconnect();
  }, [
    selectedItem?.id,
    selectedItem?.type,
    selectedItemIsQuickEditing,
    selectedItemHasImageMaskTool,
    selectedItemIsCrop,
    inlineEditPrompt,
    selectedInlineEditError,
    selectedItem?.originalPrompt,
    selectedItem?.optimizedPrompt,
    selectedItem?.prompt
  ]);

  useEffect(() => {
    if (!hasActiveCanvasGeneration) return;

    const handleBeforeUnload = (event: BeforeUnloadEvent) => {
      event.preventDefault();
      event.returnValue = '';
    };

    window.addEventListener('beforeunload', handleBeforeUnload);
    return () => window.removeEventListener('beforeunload', handleBeforeUnload);
  }, [hasActiveCanvasGeneration]);

  useEffect(() => {
    if (!cropItemId || selectedIds.includes(cropItemId)) return;
    setCropItemId(null);
    setCropRect(null);
    setCropDragState(null);
  }, [cropItemId, selectedIds]);

  useEffect(() => {
    if (!quickEditItemId || selectedIds.includes(quickEditItemId)) return;
    setQuickEditItemId(null);
  }, [quickEditItemId, selectedIds]);

  useEffect(() => {
    if (!selectedItemIsQuickEditing) return;
    window.requestAnimationFrame(() => {
      quickEditInputRef.current?.focus();
      quickEditInputRef.current?.select();
    });
  }, [selectedItemIsQuickEditing, selectedItem?.id]);

  useEffect(() => {
    if (!cropDragState) return;

    const handleCropMove = (event: MouseEvent) => {
      const dx = (event.clientX - cropDragState.startX) / zoom;
      const dy = (event.clientY - cropDragState.startY) / zoom;
      if (cropDragState.mode === 'move') {
        setCropRect(clampCropRect({
          ...cropDragState.startRect,
          x: cropDragState.startRect.x + dx,
          y: cropDragState.startRect.y + dy
        }, cropDragState.itemWidth, cropDragState.itemHeight));
        return;
      }

      setCropRect(resizeCropRect(
        cropDragState.startRect,
        cropDragState.mode,
        dx,
        dy,
        cropDragState.itemWidth,
        cropDragState.itemHeight
      ));
    };

    const handleCropEnd = () => setCropDragState(null);
    window.addEventListener('mousemove', handleCropMove);
    window.addEventListener('mouseup', handleCropEnd);
    return () => {
      window.removeEventListener('mousemove', handleCropMove);
      window.removeEventListener('mouseup', handleCropEnd);
    };
  }, [cropDragState, zoom]);

  // 修复假死：当选中项改变时，确保重置绘图状态，防止 Ref 冲突或状态死循环
  useEffect(() => {
    const canUseDrawingTool = selectedItem?.type === 'workflow' || selectedItemHasImageMaskTool;
    if (!canUseDrawingTool || activeTool === 'select') {
      setIsDrawing(false);
      if (activeTool !== 'select') setActiveTool('select');
    }
  }, [activeTool, selectedIds, localRedrawItemId, localRemoverItemId, selectedItem?.type, selectedItemHasImageMaskTool]);

  useEffect(() => {
    if (!selectedItemHasImageMaskTool || !selectedItem) return;

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
  }, [selectedItemHasImageMaskTool, selectedItem?.id, selectedItem?.maskData, selectedItem?.width, selectedItem?.height]);

  useEffect(() => {
    if (selectedItem?.type !== 'workflow' || activeTool === 'select') return;

    const canvas = drawingCanvasRef.current;
    if (!canvas) return;

    const width = getCanvasDimension(selectedItem.width);
    const height = getCanvasDimension(selectedItem.height);
    canvas.width = width;
    canvas.height = height;

    const context = canvas.getContext('2d');
    if (!context) return;

    context.clearRect(0, 0, width, height);
    if (!selectedItem.drawingData) return;

    let cancelled = false;
    loadImageElement(selectedItem.drawingData)
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
  }, [activeTool, selectedItem?.drawingData, selectedItem?.height, selectedItem?.id, selectedItem?.type, selectedItem?.width]);

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
    if ((localRedrawItemId === id || localRemoverItemId === id) && maskCanvasRef.current) {
      return maskCanvasRef.current.toDataURL('image/png');
    }
    return fallbackMaskData;
  };

  const resetImageToolModes = () => {
    onCanvasToolChange('select');
    setLocalRedrawItemId(null);
    setLocalRemoverItemId(null);
    setCropItemId(null);
    setCropRect(null);
    setCropDragState(null);
    setActiveTool('select');
  };

  const toggleQuickEditMode = (item: CanvasItem) => {
    if (item.type !== 'image' || item.status !== 'completed' || !item.content) return;

    resetImageToolModes();
    clearInlineEditError(item.id);
    setQuickEditItemId(currentId => currentId === item.id ? null : item.id);
  };

  const toggleCropMode = (item: CanvasItem) => {
    setQuickEditItemId(null);
    if (cropItemId === item.id) {
      resetImageToolModes();
      return;
    }

    onCanvasToolChange('select');
    setLocalRedrawItemId(null);
    setLocalRemoverItemId(null);
    setActiveTool('select');
    setCropItemId(item.id);
    setCropRect(createInitialCropRect(item));
    setCropDragState(null);
  };

  const toggleLocalRedrawMode = (item: CanvasItem) => {
    setQuickEditItemId(null);
    onCanvasToolChange('select');
    setCropItemId(null);
    setCropRect(null);
    setCropDragState(null);
    const nextId = localRedrawItemId === item.id ? null : item.id;
    setLocalRedrawItemId(nextId);
    if (nextId) setLocalRemoverItemId(null);
    setActiveTool(nextId ? 'brush' : 'select');
    if (nextId) onImagePromptRequest(item, undefined, 'local-redraw');
  };

  const toggleRemoverMode = (item: CanvasItem) => {
    setQuickEditItemId(null);
    onCanvasToolChange('select');
    setCropItemId(null);
    setCropRect(null);
    setCropDragState(null);
    const nextId = localRemoverItemId === item.id ? null : item.id;
    setLocalRemoverItemId(nextId);
    if (nextId) setLocalRedrawItemId(null);
    setActiveTool(nextId ? 'brush' : 'select');
    if (nextId) onImagePromptRequest(item, REMOVER_PROMPT, 'remover');
  };

  const applyQuickEditPrompt = (item: CanvasItem, prompt: string) => {
    resetImageToolModes();
    setQuickEditItemId(null);
    onImagePromptRequest(item, prompt);
  };

  const handleDownloadSelectedImage = async (item: CanvasItem) => {
    const imageSource = getOriginalImageSrc(item) || getCanvasImageSrc(item);
    if (!imageSource || item.status !== 'completed') return;

    clearInlineEditError(item.id);
    setDownloadingImageId(item.id);

    try {
      if (imageSource.startsWith('data:')) {
        const extension = getImageFileExtension(imageSource, getImageDataUrlMimeType(imageSource));
        triggerBrowserDownload(imageSource, buildImageDownloadFilename(item, extension));
        return;
      }

      const response = await fetch(imageSource, { credentials: 'same-origin' });
      if (!response.ok) {
        throw new Error(`下载图片失败：${response.status}`);
      }

      const blob = await response.blob();
      const extension = getImageFileExtension(imageSource, blob.type);
      const objectUrl = URL.createObjectURL(blob);
      triggerBrowserDownload(objectUrl, buildImageDownloadFilename(item, extension));
      window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
    } catch (error) {
      const message = error instanceof Error ? error.message : '下载图片失败';
      setInlineEditErrors(prev => ({ ...prev, [item.id]: message }));
    } finally {
      setDownloadingImageId(prev => prev === item.id ? null : prev);
    }
  };

  const handleConfirmCrop = async () => {
    if (!selectedItem || selectedItem.type !== 'image' || !cropRect) return;

    try {
      onBeforeCanvasMutation();
      const { dataUrl, frame } = await createCroppedImageDataUrl(selectedItem, cropRect);
      const nextZIndex = Math.max(0, ...items.map(item => item.zIndex || 0)) + 1;
      const newId = Math.random().toString(36).substr(2, 9);
      const cropPosition = findRightSideCanvasPosition(items, selectedItem, frame.width, frame.height);
      const croppedItem: CanvasItem = {
        id: newId,
        type: 'image',
        content: dataUrl,
        x: cropPosition.x,
        y: cropPosition.y,
        width: frame.width,
        height: frame.height,
        status: 'completed',
        label: '裁剪版本',
        zIndex: nextZIndex,
        parentId: selectedItem.id,
        prompt: `从 ${selectedItem.id} 裁剪生成`,
        originalPrompt: `从 ${selectedItem.id} 裁剪生成`,
        optimizedPrompt: `从 ${selectedItem.id} 裁剪生成`,
        layers: []
      };

      onItemAdd(croppedItem);
      resetImageToolModes();
      setSelectedIds([newId]);
    } catch (error) {
      const message = error instanceof Error ? error.message : '裁剪失败';
      setInlineEditErrors(prev => ({ ...prev, [selectedItem.id]: message }));
    }
  };

  useEffect(() => {
    const handleKeyDown = (e: KeyboardEvent) => {
      if (isEditableTarget(e.target)) return;

      if (e.key === 'Tab' && selectedItemCanQuickEdit && selectedItem) {
        e.preventDefault();
        toggleQuickEditMode(selectedItem);
        return;
      }

      if (e.code === 'Space') {
        setIsSpacePressed(true);
        if (e.target === document.body) e.preventDefault();
      }
      if ((e.key === 'Delete' || e.key === 'Backspace') && selectedIds.length > 0) {
        onBeforeCanvasMutation();
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
  }, [onBeforeCanvasMutation, onItemDeleteMultiple, selectedIds, selectedItem, selectedItemCanQuickEdit]);

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
    if (isSpacePressed || canvasTool === 'pan') {
      onBeforeCanvasMutation();
      setIsPanning(true);
      setLastMousePos({ x: e.clientX, y: e.clientY });
      return;
    }

    // 图片局部重绘蒙版：置顶层画布捕获
    if (activeTool !== 'select' && selectedItemHasImageMaskTool) {
      onBeforeCanvasMutation();
      setIsDrawing(true);
      const rect = maskCanvasRef.current?.getBoundingClientRect();
      if (rect) {
        const ctx = maskCanvasRef.current?.getContext('2d');
        if (ctx) {
          const x = (e.clientX - rect.left) / zoom;
          const y = (e.clientY - rect.top) / zoom;
          maskStrokePointsRef.current = selectedItemIsRemover && activeTool === 'brush' ? [{ x, y }] : [];
          ctx.beginPath();
          ctx.lineCap = 'round';
          ctx.lineJoin = 'round';
          ctx.lineWidth = brushSize;
          ctx.strokeStyle = activeImageMaskStrokeColor;
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
      onBeforeCanvasMutation();
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

    if (isDrawing && selectedItemHasImageMaskTool) {
      const rect = maskCanvasRef.current?.getBoundingClientRect();
      if (rect) {
        const ctx = maskCanvasRef.current?.getContext('2d');
        if (ctx) {
          const x = (e.clientX - rect.left) / zoom;
          const y = (e.clientY - rect.top) / zoom;
          if (selectedItemIsRemover && activeTool === 'brush') {
            maskStrokePointsRef.current = [...maskStrokePointsRef.current, { x, y }];
          }
          ctx.strokeStyle = activeImageMaskStrokeColor;
          ctx.lineWidth = brushSize;
          ctx.globalCompositeOperation = activeTool === 'eraser' ? 'destination-out' : 'source-over';
          ctx.lineTo(x, y);
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
    if (isDrawing && selectedItemHasImageMaskTool) {
      setIsDrawing(false);
      if (selectedItemIsRemover && activeTool === 'brush') {
        fillRemoverLassoSelection(maskCanvasRef.current, maskStrokePointsRef.current, brushSize);
      }
      maskStrokePointsRef.current = [];
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
    if (activeTool !== 'select' || isSpacePressed || canvasTool !== 'select') return;
    if ((e.target as HTMLElement).tagName === 'TEXTAREA' || (e.target as HTMLElement).tagName === 'INPUT') return;
    if (e.button !== 0) return; 
    
    e.stopPropagation();
    onBeforeCanvasMutation();
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
          const source = getCanvasImageSrc(item) || item.compositeImage || '';
          if (!source) continue;
          const img = new Image();
          img.crossOrigin = "anonymous";
          img.src = source;
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
      
      const imageResult = await generateWorkflowImage(frameworkPrompt, snapshot);
      const result = imageResult.image;
      
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
        prompt: frameworkPrompt || imageResult.originalPrompt || '视觉逻辑生成',
        originalPrompt: frameworkPrompt || imageResult.originalPrompt || '视觉逻辑生成',
        optimizedPrompt: imageResult.optimizedPrompt || imageResult.originalPrompt || frameworkPrompt || '视觉逻辑生成',
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
    const rawPrompt = inlineEditPrompt.trim();
    const isRemoverMode = selectedItem?.type === 'image' && localRemoverItemId === selectedItem.id;
    if ((!rawPrompt && !isRemoverMode) || !selectedItem || selectedItem.type !== 'image' || inlineEditingIdsRef.current.has(selectedItem.id)) return;

    const targetItem = selectedItem;
    const prompt = isRemoverMode
      ? rawPrompt
        ? `${REMOVER_PROMPT} 用户补充要求：${rawPrompt}`
        : REMOVER_PROMPT
      : rawPrompt;
    const useLocalMask = localRedrawItemId === targetItem.id || localRemoverItemId === targetItem.id;
    let resultItemId: string | null = null;
    setInlineEditingForItem(targetItem.id, true);
    clearInlineEditError(targetItem.id);

    try {
      const currentMaskData = useLocalMask
        ? getCurrentMaskDataForItem(targetItem.id, targetItem.maskData)
        : undefined;

      if (useLocalMask && !currentMaskData) {
        throw new Error(isRemoverMode ? '请先用画笔涂抹需要删除的物体' : '请先用画笔涂抹需要局部重绘的区域');
      }

      const promptToOptimize = useLocalMask
        ? isRemoverMode
          ? `${prompt}。只删除或修复用户用蒙版涂抹的局部区域，未被蒙版覆盖的区域必须保持原图不变。`
          : `${prompt}。只修改用户用蒙版涂抹的局部区域，未被蒙版覆盖的区域必须保持原图不变。`
        : prompt;
      const editReferences = useLocalMask
        ? [
          targetItem,
          ...resolveMentionedImageReferences(prompt, items).filter(item => item.id !== targetItem.id)
        ]
        : await resolveEditReferencesWithAi(prompt, targetItem, items);
      const editBaseItem = editReferences[0] || targetItem;
      const referenceImages = editReferences.slice(1)
        .filter(item => item.id !== editBaseItem.id);
      const generationPrompt = referenceImages.length > 0
        ? buildReferencedImageEditPrompt(promptToOptimize, editBaseItem, referenceImages, { hasLocalMask: useLocalMask, allItems: items })
        : promptToOptimize;
      const effectiveAspectRatio = isRemoverMode || inlineEditAspectRatio === 'auto'
        ? inferAspectRatioFromDimensions(editBaseItem.width, editBaseItem.height)
        : inlineEditAspectRatio;

      const persistentEditImages = await Promise.all(
        [editBaseItem, ...referenceImages].map(item => ensureCanvasImageAsset(item))
      );
      const persistentTargetItem = persistentEditImages[0];
      const persistentReferenceImages = persistentEditImages.slice(1);
      for (const item of persistentEditImages) {
        onItemUpdate(item.id, {
          content: item.content,
          assetId: item.assetId,
          previewContent: item.previewContent,
          thumbnailContent: item.thumbnailContent
        });
      }
      const referenceContents = persistentReferenceImages.map(item => item.content);
      const imageContext = buildImageReferenceRoleContext(
        promptToOptimize,
        persistentTargetItem,
        persistentReferenceImages,
        { hasLocalMask: useLocalMask, allItems: items }
      );
      const removerImageContext = isRemoverMode
        ? [
          '任务类型：image-remover / object removal / inpainting。',
          '这不是普通图生图，也不是 logo 强化；模型必须删除 mask 透明区域内的内容。',
          'mask 透明区域就是用户圈选/涂抹的删除区域；区域内出现的英文、中文、logo、品牌字样、图标、水印都必须移除。',
          '删除后要根据周围背景、材质、光影、透视自然补洞；未被 mask 覆盖的区域必须尽量保持原样。',
          imageContext
        ].join('\n')
        : imageContext;
      const editOptions = {
        imageAssetId: getCanvasItemAssetId(persistentTargetItem),
        referenceAssetIds: persistentReferenceImages.map(getCanvasItemAssetId).filter(Boolean),
        imageContext: removerImageContext,
        promptOptimizationMode: isRemoverMode ? 'image-remover' as const : undefined
      };

      let maskDataUrl: string | null | undefined;
      if (useLocalMask) {
        maskDataUrl = await createTransparentEditMask(
          currentMaskData!,
          persistentTargetItem.content,
          targetItem.width,
          targetItem.height,
          isRemoverMode
            ? { outlineDilationRadius: 6, editableDilationRadius: 5 }
            : undefined
        );
        if (!maskDataUrl) {
          throw new Error(isRemoverMode ? '请先用画笔涂抹需要删除的物体' : '请先用画笔涂抹需要局部重绘的区域');
        }
        onItemUpdate(targetItem.id, { maskData: currentMaskData! });
      }

      const nextZIndex = Math.max(0, ...items.map(item => item.zIndex || 0)) + 1;
      const newId = Math.random().toString(36).substr(2, 9);
      const resultMaxLongSide = Math.max(editBaseItem.width, editBaseItem.height);
      const resultFrame = getAspectRatioFrame(
        effectiveAspectRatio,
        editBaseItem.width,
        editBaseItem.height,
        resultMaxLongSide
      );
      const resultPosition = findRightSideCanvasPosition(items, editBaseItem, resultFrame.width, resultFrame.height);
      const resultItem: CanvasItem = {
        id: newId,
        type: 'image',
        content: '',
        x: resultPosition.x,
        y: resultPosition.y,
        width: resultFrame.width,
        height: resultFrame.height,
        status: 'loading',
        label: isRemoverMode ? 'AI 删除中...' : 'AI 重绘中...',
        zIndex: nextZIndex,
        parentId: editBaseItem.id,
        prompt: generationPrompt,
        originalPrompt: rawPrompt || prompt,
        layers: []
      };

      onItemAdd(resultItem);
      resultItemId = newId;
      setSelectedIds([newId]);

      let imageResult: ImageGenerationResult;
      let latestOptimizedPrompt = '';
      const requestAspectRatio = isRemoverMode ? 'auto' : effectiveAspectRatio;
      if (canUseImageJobs()) {
        const job = await submitImageEditJob(
          generationPrompt,
          persistentTargetItem.content,
          maskDataUrl || undefined,
          requestAspectRatio,
          referenceContents,
          editOptions
        );
        latestOptimizedPrompt = job.optimizedPrompt || '';
        onItemUpdate(newId, {
          imageJobId: job.jobId,
          optimizedPrompt: job.optimizedPrompt
            ? normalizeOptimizedPromptImageReferences(job.optimizedPrompt, persistentTargetItem, persistentReferenceImages, items)
            : undefined
        });
        imageResult = await waitForImageJob(job.jobId);
      } else {
        imageResult = await editImage(
          generationPrompt,
          persistentTargetItem.content,
          maskDataUrl || undefined,
          requestAspectRatio,
          referenceContents,
          editOptions
        );
      }
      const result = imageResult.image;
      const rawOptimizedPrompt = imageResult.optimizedPrompt || latestOptimizedPrompt || imageResult.originalPrompt || generationPrompt;
      const optimizedPrompt = normalizeOptimizedPromptImageReferences(
        rawOptimizedPrompt,
        persistentTargetItem,
        persistentReferenceImages,
        items
      );
      onItemUpdate(newId, {
        ...centerFrameOnRect(resultItem, resultFrame),
        content: result,
        status: 'completed',
        imageJobId: undefined,
        originalPrompt: rawPrompt || prompt,
        optimizedPrompt,
        label: isRemoverMode
          ? '删除物体'
          : rawPrompt.substring(0, 16) + (rawPrompt.length > 16 ? '...' : '')
      });
      setInlineEditPromptForItem(targetItem.id, '');
      setQuickEditItemId(null);
      if (isRemoverMode) {
        setLocalRedrawItemId(null);
        setLocalRemoverItemId(null);
        setActiveTool('select');
      } else if (useLocalMask) {
        setLocalRemoverItemId(null);
        setLocalRedrawItemId(null);
        setActiveTool('select');
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '未知错误';
      console.error(error);
      if (resultItemId) {
        onItemUpdate(resultItemId, {
          status: 'error',
          imageJobId: undefined,
          label: isRemoverMode ? 'AI 删除失败' : 'AI 重绘失败'
        });
      }
      if (isRemoverMode) {
        setSelectedIds([targetItem.id]);
        setLocalRedrawItemId(null);
        setLocalRemoverItemId(targetItem.id);
        setActiveTool('brush');
      } else if (useLocalMask) {
        setSelectedIds([targetItem.id]);
        setLocalRemoverItemId(null);
        setLocalRedrawItemId(targetItem.id);
        setActiveTool('brush');
      }
      setInlineEditErrors(prev => ({ ...prev, [targetItem.id]: message }));
    } finally {
      setInlineEditingForItem(targetItem.id, false);
    }
  };

  const adjustZIndex = (id: string, action: 'front' | 'back') => {
    onBeforeCanvasMutation();
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
        onBeforeCanvasMutation();
        const item = items.find(i => i.id === id);
        if (item) setResizeState({ 
          id, direction: dir, startX: e.clientX, startY: e.clientY, 
          startW: item.width, startH: item.height, startItemX: item.x, startItemY: item.y 
        });
      }} />
    );
  };

  const renderWorkflowDrawingLayer = (item: CanvasItem) => {
    if (item.type !== 'workflow') return null;

    return (selectedIds.includes(item.id) && activeTool !== 'select') ? (
      <canvas
        ref={drawingCanvasRef}
        width={item.width}
        height={item.height}
        className="absolute inset-0 z-[60] w-full h-full cursor-crosshair pointer-events-auto"
      />
    ) : item.drawingData ? (
      <img src={item.drawingData} className="absolute inset-0 z-[60] w-full h-full pointer-events-none" />
    ) : null;
  };

  const renderImageMaskLayer = (item: CanvasItem) => {
    if (item.type !== 'image') return null;

    const isActiveMaskItem = selectedItemHasImageMaskTool && selectedItem?.id === item.id;
    if (!isActiveMaskItem && !item.maskData) return null;

    return isActiveMaskItem ? (
      <canvas
        ref={maskCanvasRef}
        width={getCanvasDimension(item.width)}
        height={getCanvasDimension(item.height)}
        className={`absolute inset-0 z-[60] h-full w-full ${
          activeTool === 'select' ? 'pointer-events-none' : 'cursor-crosshair pointer-events-auto'
        }`}
      />
    ) : item.maskData ? (
      <img src={item.maskData} className="absolute inset-0 z-[60] h-full w-full pointer-events-none" />
    ) : null;
  };

  const renderCropOverlay = (item: CanvasItem) => {
    if (item.type !== 'image' || cropItemId !== item.id || !cropRect) return null;

    const handleCropMouseDown = (event: React.MouseEvent, mode: 'move' | ResizeDirection) => {
      event.preventDefault();
      event.stopPropagation();
      setCropDragState({
        mode,
        startX: event.clientX,
        startY: event.clientY,
        startRect: cropRect,
        itemWidth: item.width,
        itemHeight: item.height
      });
    };

    const dimClass = 'absolute bg-black/35 pointer-events-none';
    const handleClass = 'absolute h-4 w-4 rounded-full border-2 border-white bg-indigo-500 shadow-lg';

    return (
      <div className="absolute inset-0 z-[80] cursor-crosshair" onMouseDown={(event) => event.stopPropagation()}>
        <div className={dimClass} style={{ left: 0, top: 0, width: item.width, height: cropRect.y }} />
        <div className={dimClass} style={{ left: 0, top: cropRect.y + cropRect.height, width: item.width, height: item.height - cropRect.y - cropRect.height }} />
        <div className={dimClass} style={{ left: 0, top: cropRect.y, width: cropRect.x, height: cropRect.height }} />
        <div className={dimClass} style={{ left: cropRect.x + cropRect.width, top: cropRect.y, width: item.width - cropRect.x - cropRect.width, height: cropRect.height }} />
        <div
          className="absolute rounded-xl border-2 border-white shadow-[0_0_0_1px_rgba(79,70,229,0.8),0_16px_40px_-16px_rgba(0,0,0,0.65)]"
          style={{ left: cropRect.x, top: cropRect.y, width: cropRect.width, height: cropRect.height }}
          onMouseDown={(event) => handleCropMouseDown(event, 'move')}
        >
          <div className="absolute inset-0 grid grid-cols-3 grid-rows-3 pointer-events-none">
            {Array.from({ length: 9 }).map((_, index) => (
              <div key={index} className="border border-white/30" />
            ))}
          </div>
          {(['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as ResizeDirection[]).map(direction => (
            <button
              key={direction}
              type="button"
              className={handleClass}
              style={{
                cursor: `${direction}-resize`,
                ...(direction.includes('n') ? { top: -8 } : direction.includes('s') ? { bottom: -8 } : { top: '50%', marginTop: -8 }),
                ...(direction.includes('w') ? { left: -8 } : direction.includes('e') ? { right: -8 } : { left: '50%', marginLeft: -8 })
              }}
              onMouseDown={(event) => handleCropMouseDown(event, direction)}
              aria-label={`调整裁剪框 ${direction}`}
            />
          ))}
        </div>
      </div>
    );
  };

  const contextMenuItem = items.find(i => i.id === contextMenu?.id);

  return (
    <div 
      ref={containerRef}
      className={`flex-1 relative overflow-hidden canvas-grid bg-[#fcfcfc] select-none ${isSpacePressed || canvasTool === 'pan' ? 'cursor-grab active:cursor-grabbing' : 'cursor-default'}`}
      style={{ backgroundColor }}
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
        className="absolute will-change-transform"
        style={{ transform: `translate(${pan.x}px, ${pan.y}px) scale(${zoom})`, transformOrigin: '0 0' }}
      >
        {/* 底层内容 */}
        {items.map((item) => (
          <div
            key={item.id}
            onMouseDown={(e) => startItemDrag(e, item.id)}
            onContextMenu={(e) => handleItemContextMenu(e, item.id)}
            className={`absolute ${
              item.type === 'image'
                ? selectedIds.includes(item.id) ? 'outline outline-2 outline-indigo-500' : ''
                : `rounded-[16px] transition-shadow duration-300 ${
                  selectedIds.includes(item.id) ? 'ring-2 ring-indigo-500 shadow-2xl' : 'shadow-lg'
                }`
            } ${item.type === 'workflow' ? 'border-2 border-dashed border-indigo-200' : ''}`}
            style={{ 
              left: item.x, top: item.y, width: item.width, height: item.height, 
              zIndex: item.zIndex || 0,
              backgroundColor: item.type === 'image' || item.type === 'text' ? 'transparent' : '#fff'
            }}
          >
            <div className={`relative h-full w-full overflow-hidden ${item.type === 'image' ? '' : 'rounded-[14px]'}`}>
              {item.type === 'text' ? (
                <textarea
                  value={item.content}
                  onChange={(e) => onItemUpdate(item.id, { content: e.target.value })}
                  className="w-full h-full p-3 bg-transparent outline-none resize-none font-bold text-gray-800"
                />
              ) : item.type === 'workflow' ? (
                <div className="w-full h-full bg-white/30" />
              ) : (() => {
                const imageSrc = getCanvasImageSrc(item);
                return imageSrc ? (
                  <img src={imageSrc} className="w-full h-full object-contain pointer-events-none" />
                ) : item.status === 'loading' ? (
                  <div className="h-full w-full bg-zinc-50" />
                ) : (
                  <div className="flex h-full w-full items-center justify-center bg-gray-50 text-xs font-bold text-gray-300">
                    等待图片生成
                  </div>
                );
              })()}
              {item.type === 'image' && item.status === 'loading' && (
                <ImageGenerationSkeleton hasPreview={!!getCanvasImageSrc(item)} />
              )}
              {renderWorkflowDrawingLayer(item)}
              {renderImageMaskLayer(item)}
              {renderCropOverlay(item)}
            </div>

            {item.type !== 'image' && selectedIds.length === 1 && selectedIds[0] === item.id && (
              (['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as ResizeDirection[]).map(dir => renderResizeHandle(item.id, dir))
            )}
          </div>
        ))}

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

        {selectedItem?.type === 'image' && selectedIds.length === 1 && !isDraggingSelectedImage && (
          <div
            className="absolute z-[2000000] pointer-events-auto"
            style={{
              left: selectedImageToolbarPosition.left,
              top: selectedImageToolbarPosition.top
            }}
          >
            <div
              ref={imageToolbarRef}
              className="flex flex-col items-center gap-2 animate-in fade-in zoom-in-95"
              style={{
                transform: selectedImageToolbarPosition.transform,
                transformOrigin: selectedImageToolbarPosition.transformOrigin
              }}
              onMouseDown={(event) => event.stopPropagation()}
            >
            <div className="relative h-11 w-max overflow-hidden rounded-[14px] border border-zinc-200 bg-white pr-11 shadow-[0_14px_40px_-26px_rgba(0,0,0,0.5)]">
              <div className="flex h-full items-center pr-2">
                <button
                  type="button"
                  onClick={() => toggleQuickEditMode(selectedItem)}
                  disabled={!selectedItemCanQuickEdit || selectedItemIsInlineEditing}
                  className={`flex h-11 shrink-0 items-center gap-2 border-r border-zinc-100 px-3 text-xs font-bold transition-colors ${
                    selectedItemIsQuickEditing
                      ? 'bg-zinc-900 text-white'
                      : 'text-zinc-700 hover:bg-zinc-50 hover:text-zinc-950'
                  } disabled:cursor-not-allowed disabled:opacity-35`}
                  title="快捷编辑当前图片（Tab）"
                >
                  <Sparkles size={15} className={selectedItemIsQuickEditing ? 'text-white' : 'text-zinc-500'} />
                  快捷编辑 <span className="text-[10px] font-bold text-zinc-300">Tab</span>
                </button>
                <button
                  type="button"
                  onClick={() => toggleCropMode(selectedItem)}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className={`flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold transition-colors ${
                    selectedItemIsCrop
                      ? 'bg-zinc-100 text-zinc-950'
                      : 'text-zinc-700 hover:bg-zinc-50 hover:text-zinc-950'
                  } disabled:opacity-35`}
                  title="拖动裁剪框，保存为新的派生图片节点"
                >
                  <Scissors size={15} />裁剪
                </button>
                <button
                  type="button"
                  onClick={() => toggleLocalRedrawMode(selectedItem)}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className={`flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold transition-colors ${
                    selectedItemIsLocalRedraw
                      ? 'bg-zinc-100 text-zinc-950'
                      : 'text-zinc-700 hover:bg-zinc-50 hover:text-zinc-950'
                  } disabled:opacity-35`}
                >
                  <Layers size={15} />编辑元素
                </button>
                <button
                  type="button"
                  onClick={() => toggleRemoverMode(selectedItem)}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className={`flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold transition-colors ${
                    selectedItemIsRemover
                      ? 'bg-zinc-100 text-red-600'
                      : 'text-zinc-700 hover:bg-zinc-50 hover:text-zinc-950'
                  } disabled:opacity-35`}
                  title="涂抹物体后一键删除并补全背景"
                >
                  <Trash2 size={15} />橡皮工具
                </button>
                <button
                  type="button"
                  onClick={() => applyQuickEditPrompt(selectedItem, '更换背景，保留主体、姿态、服装、材质和光影自然一致。请在这里补充新背景：')}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-zinc-700 transition-colors hover:bg-zinc-50 hover:text-zinc-950 disabled:opacity-35"
                >
                  <ImagePlus size={15} />换背景
                </button>
                <button
                  type="button"
                  onClick={() => applyQuickEditPrompt(selectedItem, '修改指定物体颜色，保持材质、纹理、光影和其他内容不变。请在这里补充对象和颜色：')}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-zinc-700 transition-colors hover:bg-zinc-50 hover:text-zinc-950 disabled:opacity-35"
                >
                  <Palette size={15} />改颜色
                </button>
                <button
                  type="button"
                  onClick={() => applyQuickEditPrompt(selectedItem, '增强图片细节和清晰度，保持原图构图、主体身份、色彩和风格不变，不要新增无关元素。')}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-zinc-700 transition-colors hover:bg-zinc-50 hover:text-zinc-950 disabled:opacity-35"
                >
                  <Wand2 size={15} />增强
                </button>
                <button
                  type="button"
                  onClick={() => applyQuickEditPrompt(selectedItem, '向画面外自然扩图，延展背景、光影、透视和材质，保持主体不变。可先在“画幅”里选择更大的目标比例。')}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-zinc-700 transition-colors hover:bg-zinc-50 hover:text-zinc-950 disabled:opacity-35"
                >
                  <Maximize2 size={15} />放大
                </button>
                {selectedItemIsCrop && (
                  <>
                    <span className="flex h-11 shrink-0 items-center border-r border-zinc-100 px-3 text-[11px] font-bold text-blue-600">拖动裁剪框</span>
                    <button
                      type="button"
                      onClick={handleConfirmCrop}
                      className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-emerald-600 transition-colors hover:bg-emerald-50"
                    >
                      <Check size={15} />保存为新图
                    </button>
                    <button
                      type="button"
                      onClick={resetImageToolModes}
                      className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-zinc-500 transition-colors hover:bg-zinc-50 hover:text-zinc-950"
                    >
                      <X size={15} />取消
                    </button>
                  </>
                )}
                {selectedItemHasImageMaskTool && (
                  <>
                    <span className={`flex h-11 shrink-0 items-center border-r border-zinc-100 px-3 text-[11px] font-bold ${selectedItemIsRemover ? 'text-red-600' : 'text-blue-600'}`}>
                      {selectedItemIsRemover ? '删除范围' : '重绘范围'}
                    </span>
                    <button type="button" onClick={() => setActiveTool('select')} className={`flex h-11 shrink-0 items-center border-r border-zinc-100 px-3 transition-colors ${activeTool === 'select' ? 'bg-zinc-100 text-zinc-950' : 'text-zinc-500 hover:bg-zinc-50 hover:text-zinc-950'}`} title="选择/移动">
                      <MousePointer2 size={16} />
                    </button>
                    <button type="button" onClick={() => setActiveTool('brush')} className={`flex h-11 shrink-0 items-center border-r border-zinc-100 px-3 transition-colors ${activeTool === 'brush' ? 'bg-zinc-100 text-zinc-950' : 'text-zinc-500 hover:bg-zinc-50 hover:text-zinc-950'}`} title="涂抹区域">
                      <Pencil size={16} />
                    </button>
                    <button type="button" onClick={() => setActiveTool('eraser')} className={`flex h-11 shrink-0 items-center border-r border-zinc-100 px-3 transition-colors ${activeTool === 'eraser' ? 'bg-zinc-100 text-zinc-950' : 'text-zinc-500 hover:bg-zinc-50 hover:text-zinc-950'}`} title="擦除蒙版">
                      <Eraser size={16} />
                    </button>
                    <div className="flex h-11 shrink-0 items-center border-r border-zinc-100 px-3">
                      <input
                        type="range"
                        min={4}
                        max={64}
                        value={brushSize}
                        onChange={(event) => setBrushSize(Number(event.target.value))}
                        className={`h-1 w-24 ${selectedItemIsRemover ? 'accent-red-500' : 'accent-zinc-800'}`}
                        title="画笔大小"
                      />
                    </div>
                    <button
                      type="button"
                      onClick={() => onItemUpdate(selectedItem.id, { maskData: undefined })}
                      className="flex h-11 shrink-0 items-center border-r border-zinc-100 px-3 text-xs font-bold text-zinc-500 transition-colors hover:bg-red-50 hover:text-red-500"
                    >
                      清除
                    </button>
                  </>
                )}
              </div>
              <button
                type="button"
                onClick={() => handleDownloadSelectedImage(selectedItem)}
                disabled={!getOriginalImageSrc(selectedItem) || selectedItem.status !== 'completed' || downloadingImageId === selectedItem.id}
                className="absolute right-0 top-0 flex h-11 w-11 items-center justify-center border-l border-zinc-100 text-xs font-bold text-zinc-700 transition-colors hover:bg-zinc-50 hover:text-zinc-950 active:bg-zinc-100 disabled:cursor-not-allowed disabled:opacity-35"
                title="直接下载当前选中的原始图片"
              >
                {downloadingImageId === selectedItem.id ? <Loader2 size={15} className="animate-spin" /> : <Download size={15} />}
              </button>
            </div>
            {selectedItemIsQuickEditing && (
              <form
                onSubmit={handleInlineImageRedraw}
                className="flex w-[560px] max-w-[calc(100vw-32px)] items-center gap-2 rounded-[18px] border border-zinc-200 bg-white p-1.5 shadow-[0_16px_44px_-30px_rgba(0,0,0,0.45)]"
              >
                <div className="flex h-10 min-w-0 flex-1 items-center gap-2 rounded-[14px] bg-zinc-50 px-3">
                  <Sparkles size={15} className="shrink-0 text-zinc-400" />
                  <input
                    ref={quickEditInputRef}
                    value={inlineEditPrompt}
                    onChange={(event) => setInlineEditPromptForItem(selectedItem.id, event.target.value)}
                    onKeyDown={(event) => {
                      if (event.key === 'Escape') {
                        event.preventDefault();
                        setQuickEditItemId(null);
                      }
                    }}
                    disabled={selectedItemIsInlineEditing}
                    placeholder="输入一句话快捷编辑这张图..."
                    className="min-w-0 flex-1 bg-transparent text-sm font-semibold text-zinc-800 outline-none placeholder:text-zinc-400"
                  />
                  <span className="hidden shrink-0 text-[10px] font-black uppercase tracking-widest text-zinc-300 sm:block">Esc 关闭</span>
                </div>
                <button
                  type="submit"
                  disabled={!inlineEditPrompt.trim() || selectedItemIsInlineEditing}
                  className="flex h-10 w-10 shrink-0 items-center justify-center rounded-[14px] bg-zinc-900 text-white transition-all hover:bg-zinc-800 active:scale-95 disabled:cursor-not-allowed disabled:opacity-30"
                  title="提交快捷编辑"
                >
                  {selectedItemIsInlineEditing ? <Loader2 size={16} className="animate-spin" /> : <Send size={16} />}
                </button>
              </form>
            )}
            {selectedInlineEditError && (
              <div className="max-w-[520px] rounded-xl bg-red-50 border border-red-100 px-3 py-2 text-[11px] font-bold text-red-500 shadow-lg">
                {selectedInlineEditError}
              </div>
            )}
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
          <button onClick={() => { onBeforeCanvasMutation(); onItemDelete(contextMenu.id); setContextMenu(null); }} className="flex items-center gap-3 w-full px-3 py-3 hover:bg-red-50 rounded-xl text-sm font-bold text-red-500 transition-all">
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
