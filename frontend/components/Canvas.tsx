
import React, { useState, useRef, useEffect, useMemo } from 'react';
import type { AgentPlan, AgentPlanStep, CanvasItem, CanvasTool, CanvasTextStyle, ChatMessage, ImageAspectRatio } from '../types';
import { 
  Loader2, Trash2, Type, 
  Sparkles, ChevronUp, ChevronDown, 
  MousePointer2, Eraser, 
  MessageSquarePlus, Pencil,
  Copy, Layers, Scissors, Check, X,
  Palette, Maximize2, Wand2,
  Download, Send, AlignLeft, AlignCenter, AlignRight, SlidersHorizontal
} from 'lucide-react';
import { getImageJobQueueMessage, type ImageGenerationResult, type ImageJobStatus, waitForImageJob } from '../services/gemini';
import {
  centerFrameOnRect,
  fitDimensionsToLongSide,
  getAspectRatioFrame,
  getImageFrameFromSource,
} from '../services/imageSizing';
import { getCanvasImageSrc, getOriginalImageSrc } from '../services/imageSources';
import {
  normalizeOptimizedPromptImageReferences,
  resolveMentionedImageReferences
} from '../services/imageReferences';
import {
  applyAgentRunProgressEventToPlan,
  buildAgentDraftPlan,
  connectAgentRunEvents,
  createAgentRun,
  createAgentRunClientId
} from '../services/agentPlanner';
import { ensureCanvasImageAsset } from '../services/canvasPersistence';
import { formatExecutionDuration } from '../services/taskTiming';
import { buildImageResultDescription, generateImageTitleFromPrompt, getCanvasItemDisplayTitle } from '../services/imageTitle';
import { getApiConfig, getImageModelDisplayName } from '../services/config';

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
  onAddTextAt: (x: number, y: number) => string;
  onAddImageAt: (file: File, x: number, y: number) => void;
  onAddToChat: (item: CanvasItem) => void;
  onChatMessage: (text: string, role: 'user' | 'assistant', options?: Pick<ChatMessage, 'imageIds' | 'imageResultCards' | 'durationMs' | 'agentPlan'>) => void;
  onChatDraftMessage: (text: string, role: 'user' | 'assistant', options?: Pick<ChatMessage, 'imageIds' | 'imageResultCards' | 'durationMs' | 'agentPlan'>) => string;
  onChatMessageUpdate: (messageId: string, updater: (message: ChatMessage) => ChatMessage) => void;
  onImageTaskStart: (startedAt: number) => void;
  onImageTaskFinish: (startedAt: number) => void;
  onImagePromptRequest: (item: CanvasItem, prompt?: string, mode?: 'local-redraw' | 'remover') => void;
  onBeforeCanvasMutation: () => void;
  canvasTool: CanvasTool;
  onCanvasToolChange: (tool: CanvasTool) => void;
  selectedIds: string[];
  setSelectedIds: (ids: string[]) => void;
}

const isProcessDisclosureMessage = (text: string) => {
  const compact = text.replace(/\s+/g, '');
  return /我[已会将]?判断|判断这是|判断为|任务类型|文生图任务|图生图任务|意图|接下来|会先|先整理|整理提示词|再生成|最终图片|执行链路|规划步骤|当前步骤/.test(compact);
};

const ensureImageNoun = (title: string) => {
  if (!title) return '图片';
  return /(图|图片|照片|人像|作品|结果|海报|插画|场景|头像|素材)$/.test(title) ? title : `${title}图片`;
};

const buildInlineImageJobQueueNotice = (job: ImageJobStatus) => (
  getImageJobQueueMessage(job, '图片编辑任务')
);

type ResizeDirection = 'n' | 's' | 'e' | 'w' | 'ne' | 'nw' | 'se' | 'sw';
type MaskPoint = { x: number; y: number };
type ImageMaskMode = 'local-redraw' | 'remover';
type LayerSplitRole = 'subject' | 'background';
type CropRect = { x: number; y: number; width: number; height: number };
type CropDragState = {
  mode: 'move' | ResizeDirection;
  startX: number;
  startY: number;
  startRect: CropRect;
  itemWidth: number;
  itemHeight: number;
};
type CanvasRect = Pick<CanvasItem, 'x' | 'y' | 'width' | 'height'>;
type SnapGuide = {
  axis: 'x' | 'y';
  position: number;
  spanStart: number;
  spanEnd: number;
};
type SnapCandidate = {
  delta: number;
  distance: number;
  guide: SnapGuide;
};

const REMOVER_PROMPT = '把圈起来的地方删除掉。';
const BACKGROUND_REMOVAL_PROMPT = '先识别图片中的主要主体：画面最主要的人物、商品、动物、车辆或成组前景对象；主体包含其穿戴、手持、贴附和与主体直接组成整体的部分。只保留主体，把主体以外的一切背景和无关物体替换为纯白色背景（#FFFFFF），不要透明背景，不要浅灰、米白或渐变。不要改变主体 RGB 像素，不要重绘、修复、补全、美化、移动或缩放主体；严格保留原图中已经可见的主体像素、裁切范围、构图、脸、表情、姿态、服装、颜色、纹理、发丝和边缘细节；原图里被裁切到画面外的身体、头发、衣服不要补出来。输出白底图片。';
const LAYER_SPLIT_SUBJECT_PROMPT = '图层拆分：请把这张图拆出“主体层”。先识别画面主要前景主体，主体包含其穿戴、手持、贴附和直接组成整体的部分；输出同画幅主体图层，主体以外区域必须是透明 alpha，不要生成新背景，不要改变主体身份、结构、比例、颜色、材质、边缘、光影和原有裁切。';
const LAYER_SPLIT_BACKGROUND_PROMPT = '图层拆分：请把这张图拆出“背景层”。先识别画面主要前景主体，然后移除主体及其接触阴影、遮挡残影和边缘碎片；用周围背景的纹理、透视、光影、反射、噪点和景深自然补全，保持原图画幅、镜头视角和背景风格不变，不要新增主体或新场景。';

const getCanvasDimension = (value: number) => Math.max(1, Math.round(value));

const MIN_CROP_SIZE = 48;
const INLINE_IMAGE_EDITOR_GAP = 16;
const INLINE_IMAGE_TOOLBAR_HEIGHT = 44;
const QUICK_EDIT_INPUT_HEIGHT = 52;
const QUICK_EDIT_STACK_GAP = 8;
const QUICK_EDIT_FOCUS_MIN_ZOOM = 0.25;
const QUICK_EDIT_FOCUS_MAX_ZOOM = 1.45;
const CANVAS_OVERLAY_MARGIN = 16;
const DEFAULT_INLINE_IMAGE_EDITOR_SIZE = { width: 760, height: INLINE_IMAGE_TOOLBAR_HEIGHT };
const DERIVED_IMAGE_GAP = 20;
const SNAP_SCREEN_THRESHOLD = 2;
const SNAP_GUIDE_MARGIN = 28;
const TEXT_LAYER_PLACEHOLDER = '双击输入文字';
const TEXT_TOOLBAR_HEIGHT = 44;
const TEXT_TOOLBAR_WIDTH = 576;
const EMPTY_TEXT_ITEM_WIDTH = 2;
const MIN_TEXT_FONT_SIZE = 12;
const MAX_TEXT_FONT_SIZE = 200;
const DEFAULT_TEXT_STYLE: Required<CanvasTextStyle> = {
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
};
const TEXT_FONT_FAMILIES = [
  'Inter',
  'PingFang SC',
  'Noto Sans SC',
  'Microsoft YaHei',
  'Source Han Sans SC',
  'HarmonyOS Sans SC',
  'Alibaba PuHuiTi',
  'Smiley Sans',
  'Arial',
  'Helvetica',
  'Avenir Next',
  'DIN Alternate',
  'Futura',
  'Gill Sans',
  'Trebuchet MS',
  'Verdana',
  'Georgia',
  'Times New Roman',
  'Songti SC',
  'SimSun',
  'Kaiti SC',
  'KaiTi',
  'STKaiti',
  'SimHei',
  'Courier New',
  'Menlo',
  'Monaco',
  'Comic Sans MS',
  'Impact',
  'Brush Script MT'
];

const getImageMaskDataForMode = (item: CanvasItem, mode: ImageMaskMode) => (
  mode === 'remover'
    ? item.removerMaskData || item.maskData
    : item.redrawMaskData || item.maskData
);

const getImageMaskUpdateForMode = (mode: ImageMaskMode, maskData?: string): Partial<CanvasItem> => (
  mode === 'remover'
    ? { removerMaskData: maskData, maskData: undefined }
    : { redrawMaskData: maskData, maskData: undefined }
);
const TEXT_FONT_STYLES = [
  { label: 'Regular', fontWeight: 400, fontStyle: 'normal' as const },
  { label: 'Black', fontWeight: 900, fontStyle: 'normal' as const },
  { label: 'Bold', fontWeight: 700, fontStyle: 'normal' as const },
  { label: 'ExtraBold', fontWeight: 800, fontStyle: 'normal' as const },
  { label: 'ExtraLight', fontWeight: 200, fontStyle: 'normal' as const },
  { label: 'Italic', fontWeight: 400, fontStyle: 'italic' as const },
  { label: 'Light', fontWeight: 300, fontStyle: 'normal' as const },
  { label: 'Medium', fontWeight: 500, fontStyle: 'normal' as const },
  { label: 'SemiBold', fontWeight: 600, fontStyle: 'normal' as const }
];
const TEXT_FONT_SIZES = [24, 32, 48, 64, 80, 96, 120, 160];
const TEXT_STROKE_WIDTHS = [0, 1, 2, 4, 6];
const TEXT_COLOR_PALETTE = [
  '#18181b', '#ffffff', '#ef4444', '#f97316', '#f59e0b', '#eab308',
  '#22c55e', '#10b981', '#06b6d4', '#3b82f6', '#6366f1', '#8b5cf6',
  '#d946ef', '#ec4899', '#78716c', '#a1a1aa', '#000000', '#f8fafc'
];

const clampValue = (value: number, min: number, max: number) => Math.min(Math.max(value, min), max);

const getCanvasTextStyle = (item: CanvasItem): Required<CanvasTextStyle> => ({
  ...DEFAULT_TEXT_STYLE,
  ...(item.textStyle || {})
});

const getCanvasTextCss = (style: Required<CanvasTextStyle>): React.CSSProperties => ({
  color: style.color,
  backgroundColor: style.backgroundColor,
  fontSize: style.fontSize,
  fontWeight: style.fontWeight,
  fontStyle: style.fontStyle,
  textDecoration: style.textDecoration,
  textAlign: style.textAlign,
  lineHeight: style.lineHeight,
  fontFamily: `${style.fontFamily}, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`,
  whiteSpace: 'pre-wrap',
  overflowWrap: 'break-word',
  WebkitTextStroke: style.strokeWidth > 0 ? `${style.strokeWidth}px ${style.strokeColor}` : undefined,
  paintOrder: 'stroke fill'
});

const getTextContentFrame = (content: string, style: Required<CanvasTextStyle>) => {
  const lines = content.split('\n');
  const lineHeight = Math.max(1, style.fontSize * style.lineHeight);
  const fallbackWidth = Math.max(EMPTY_TEXT_ITEM_WIDTH, Math.ceil(Math.max(...lines.map(line => line.length), 1) * style.fontSize * 0.58));
  let measuredWidth = fallbackWidth;

  if (typeof document !== 'undefined') {
    const canvas = document.createElement('canvas');
    const context = canvas.getContext('2d');
    if (context) {
      context.font = `${style.fontStyle} ${style.fontWeight} ${style.fontSize}px ${style.fontFamily}, system-ui, -apple-system, BlinkMacSystemFont, "Segoe UI", sans-serif`;
      measuredWidth = Math.max(
        EMPTY_TEXT_ITEM_WIDTH,
        Math.ceil(Math.max(...lines.map(line => context.measureText(line || ' ').width)))
      );
    }
  }

  return {
    width: content.trim() ? measuredWidth + style.strokeWidth * 2 + 2 : EMPTY_TEXT_ITEM_WIDTH,
    height: Math.max(40, Math.ceil(Math.max(1, lines.length) * lineHeight + style.strokeWidth * 2 + 2))
  };
};

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

const getGroupBounds = (rects: CanvasRect[]): CanvasRect | null => {
  if (rects.length === 0) return null;

  const minX = Math.min(...rects.map(rect => rect.x));
  const minY = Math.min(...rects.map(rect => rect.y));
  const maxX = Math.max(...rects.map(rect => rect.x + rect.width));
  const maxY = Math.max(...rects.map(rect => rect.y + rect.height));
  return {
    x: minX,
    y: minY,
    width: maxX - minX,
    height: maxY - minY
  };
};

const getAxisPoints = (rect: CanvasRect, axis: 'x' | 'y') => (
  axis === 'x'
    ? [rect.x, rect.x + rect.width / 2, rect.x + rect.width]
    : [rect.y, rect.y + rect.height / 2, rect.y + rect.height]
);

const buildSnapGuide = (axis: 'x' | 'y', position: number, sourceRect: CanvasRect, targetRect: CanvasRect): SnapGuide => {
  if (axis === 'x') {
    return {
      axis,
      position,
      spanStart: Math.min(sourceRect.y, targetRect.y) - SNAP_GUIDE_MARGIN,
      spanEnd: Math.max(sourceRect.y + sourceRect.height, targetRect.y + targetRect.height) + SNAP_GUIDE_MARGIN
    };
  }

  return {
    axis,
    position,
    spanStart: Math.min(sourceRect.x, targetRect.x) - SNAP_GUIDE_MARGIN,
    spanEnd: Math.max(sourceRect.x + sourceRect.width, targetRect.x + targetRect.width) + SNAP_GUIDE_MARGIN
  };
};

const findSnapCandidate = (
  axis: 'x' | 'y',
  sourceRect: CanvasRect,
  targetRects: CanvasRect[],
  threshold: number
): SnapCandidate | null => {
  const sourcePoints = getAxisPoints(sourceRect, axis);

  return targetRects.reduce<SnapCandidate | null>((bestCandidate, targetRect) => {
    const targetPoints = getAxisPoints(targetRect, axis);

    for (const sourcePoint of sourcePoints) {
      for (const targetPoint of targetPoints) {
        const delta = targetPoint - sourcePoint;
        const distance = Math.abs(delta);
        if (distance > threshold) continue;

        if (!bestCandidate || distance < bestCandidate.distance) {
          bestCandidate = {
            delta,
            distance,
            guide: buildSnapGuide(axis, targetPoint, sourceRect, targetRect)
          };
        }
      }
    }

    return bestCandidate;
  }, null);
};

const getDraggedItemsSnapResult = (
  items: CanvasItem[],
  selectedIds: string[],
  dx: number,
  dy: number,
  zoom: number
) => {
  const selectedIdSet = new Set(selectedIds);
  const selectedRects = items.filter(item => selectedIdSet.has(item.id)).map(item => ({
    x: item.x,
    y: item.y,
    width: item.width,
    height: item.height
  }));
  const targetRects = items.filter(item => !selectedIdSet.has(item.id)).map(item => ({
    x: item.x,
    y: item.y,
    width: item.width,
    height: item.height
  }));
  const sourceBounds = getGroupBounds(selectedRects);

  if (!sourceBounds || targetRects.length === 0) {
    return { dx, dy, guides: [] as SnapGuide[] };
  }

  const safeZoom = Math.max(0.01, zoom);
  const threshold = SNAP_SCREEN_THRESHOLD / safeZoom;
  const proposedBounds = {
    ...sourceBounds,
    x: sourceBounds.x + dx,
    y: sourceBounds.y + dy
  };
  const xSnap = findSnapCandidate('x', proposedBounds, targetRects, threshold);
  const ySnap = findSnapCandidate('y', proposedBounds, targetRects, threshold);
  const guides = [xSnap?.guide, ySnap?.guide].filter((guide): guide is SnapGuide => !!guide);

  return {
    dx: dx + (xSnap?.delta || 0),
    dy: dy + (ySnap?.delta || 0),
    guides
  };
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
    if (!relevantItems.some(item => canvasRectsOverlap(candidate, item))) {
      return { x: desiredX, y: desiredY };
    }
    desiredX += width + DERIVED_IMAGE_GAP;
  }

  return { x: desiredX, y: desiredY };
};

const getLayerSplitPrompt = (role: LayerSplitRole) => (
  role === 'subject' ? LAYER_SPLIT_SUBJECT_PROMPT : LAYER_SPLIT_BACKGROUND_PROMPT
);

const getLayerSplitLabel = (role: LayerSplitRole) => (
  role === 'subject' ? '主体层' : '背景层'
);

const getLayerSplitLoadingLabel = (role: LayerSplitRole) => (
  role === 'subject' ? 'AI 提取主体层中...' : 'AI 生成背景层中...'
);

const buildLayerSplitDraftSteps = (): AgentPlanStep[] => [
  {
    id: 'identify-layer-subject',
    title: '识别主体',
    description: '识别图片中的主要前景主体。',
    type: 'analysis',
    status: 'running'
  },
  {
    id: 'optimize-layer-split',
    title: '规划拆层',
    description: '分别准备主体层和背景层的编辑指令。',
    type: 'prompt',
    status: 'pending'
  },
  {
    id: 'run-layer-split',
    title: '执行拆层',
    description: '并行创建主体层和背景层图片任务。',
    type: 'edit',
    status: 'pending'
  },
  {
    id: 'wait-image-job',
    title: '等待生成',
    description: '等待两个图层生成完成。',
    type: 'edit',
    status: 'pending'
  }
];

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
  const source = getCanvasImageSrc(item);
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

const fillImageMaskLassoSelection = (
  canvas: HTMLCanvasElement | null,
  points: MaskPoint[],
  brushSize: number,
  strokeColor: string,
  fillColor: string
) => {
  if (!canvas || points.length < 3) return;

  const area = getPolygonArea(points);
  if (area < Math.max(64, brushSize * brushSize * 1.5)) return;

  const firstPoint = points[0];
  const lastPoint = points[points.length - 1];
  const closingDistance = Math.hypot(lastPoint.x - firstPoint.x, lastPoint.y - firstPoint.y);
  if (closingDistance > Math.max(36, brushSize * 4)) return;

  const context = canvas.getContext('2d');
  if (!context) return;

  context.save();
  context.globalCompositeOperation = 'source-over';
  context.lineCap = 'round';
  context.lineJoin = 'round';
  context.lineWidth = brushSize;
  context.strokeStyle = strokeColor;
  context.fillStyle = fillColor;
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

const escapeSvgText = (value: string) => value
  .replace(/&/g, '&amp;')
  .replace(/</g, '&lt;')
  .replace(/>/g, '&gt;')
  .replace(/"/g, '&quot;')
  .replace(/'/g, '&apos;');

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
  const filenameBase = sanitizeDownloadFilename(getCanvasItemDisplayTitle(item) || `image-${item.id}`);
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
  items, zoom, onZoomChange, pan, onPanChange, backgroundColor, onItemUpdate, onItemDelete, onItemDeleteMultiple, onItemAdd, onAddTextAt, onAddImageAt, onAddToChat, onChatMessage, onChatDraftMessage, onChatMessageUpdate, onImageTaskStart, onImageTaskFinish, onImagePromptRequest, onBeforeCanvasMutation, canvasTool, onCanvasToolChange, selectedIds, setSelectedIds
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
  const [snapGuides, setSnapGuides] = useState<SnapGuide[]>([]);

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
  const [inlineEditingStartedAt, setInlineEditingStartedAt] = useState<Record<string, number>>({});
  const [inlineTimerNow, setInlineTimerNow] = useState(() => Date.now());
  const [inlineEditAspectRatio, setInlineEditAspectRatio] = useState<ImageAspectRatio>('auto');
  const [localRedrawItemId, setLocalRedrawItemId] = useState<string | null>(null);
  const [localRemoverItemId, setLocalRemoverItemId] = useState<string | null>(null);
  const [quickEditItemId, setQuickEditItemId] = useState<string | null>(null);
  const [editingTextItemId, setEditingTextItemId] = useState<string | null>(null);
  const [cropItemId, setCropItemId] = useState<string | null>(null);
  const [cropRect, setCropRect] = useState<CropRect | null>(null);
  const [cropDragState, setCropDragState] = useState<CropDragState | null>(null);
  const [downloadingImageId, setDownloadingImageId] = useState<string | null>(null);
  const inlineEditingIdsRef = useRef<Set<string>>(new Set());
  const maskCanvasRef = useRef<HTMLCanvasElement | null>(null);
  const quickEditInputRef = useRef<HTMLInputElement | null>(null);
  const textEditorRef = useRef<HTMLTextAreaElement | null>(null);
  const [activeTextPopover, setActiveTextPopover] = useState<'fill' | 'stroke' | 'font' | 'style' | 'size' | null>(null);
  const [textFontSearch, setTextFontSearch] = useState('');
  const maskStrokePointsRef = useRef<MaskPoint[]>([]);
  const imageToolbarRef = useRef<HTMLDivElement | null>(null);
  const [canvasViewportSize, setCanvasViewportSize] = useState({ width: 0, height: 0 });
  const [inlineEditorSize, setInlineEditorSize] = useState(DEFAULT_INLINE_IMAGE_EDITOR_SIZE);

  const containerRef = useRef<HTMLDivElement>(null);
  const selectedItem = items.find(i => selectedIds.length === 1 && i.id === selectedIds[0]);
  const selectedItemIsText = selectedItem?.type === 'text';
  const selectedTextStyle = selectedItemIsText ? getCanvasTextStyle(selectedItem) : DEFAULT_TEXT_STYLE;
  const selectedTextIsEditing = selectedItemIsText && selectedItem.id === editingTextItemId;
  const isDraggingSelectedImage = !!dragState && selectedItem?.type === 'image' && selectedIds.includes(dragState.id);
  const selectedItemIsInlineEditing = selectedItem ? inlineEditingIds.has(selectedItem.id) : false;
  const selectedItemIsLocalRedraw = selectedItem?.type === 'image' && selectedItem.id === localRedrawItemId;
  const selectedItemIsRemover = selectedItem?.type === 'image' && selectedItem.id === localRemoverItemId;
  const selectedItemCanQuickEdit = selectedItem?.type === 'image' && selectedItem.status === 'completed' && !!selectedItem.content;
  const selectedItemIsQuickEditing = !!selectedItemCanQuickEdit && selectedItem?.id === quickEditItemId;
  const selectedItemIsCrop = selectedItem?.type === 'image' && selectedItem.id === cropItemId;
  const selectedItemHasImageMaskTool = selectedItemIsLocalRedraw || selectedItemIsRemover;
  const selectedImageMaskMode: ImageMaskMode | null = selectedItemIsRemover
    ? 'remover'
    : selectedItemIsLocalRedraw ? 'local-redraw' : null;
  const selectedImageMaskData = selectedItem?.type === 'image' && selectedImageMaskMode
    ? getImageMaskDataForMode(selectedItem, selectedImageMaskMode)
    : undefined;
  const activeImageMaskStrokeColor = selectedItemIsRemover ? 'rgba(239, 68, 68, 0.55)' : 'rgba(99, 102, 241, 0.55)';
  const selectedInlineEditError = selectedItem ? inlineEditErrors[selectedItem.id] : '';
  const inlineEditPrompt = selectedItem?.type === 'image' ? inlineEditPrompts[selectedItem.id] || '' : '';
  const selectedInlineEditStartedAt = selectedItem ? inlineEditingStartedAt[selectedItem.id] : undefined;
  const selectedInlineEditDurationText = selectedItemIsInlineEditing && selectedInlineEditStartedAt
    ? formatExecutionDuration(inlineTimerNow - selectedInlineEditStartedAt)
    : '';
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
  useEffect(() => {
    setActiveTextPopover(null);
    setTextFontSearch('');
  }, [selectedItem?.id, selectedItem?.type]);
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
  const handleCanvasImageLoad = (
    event: React.SyntheticEvent<HTMLImageElement>,
    item: CanvasItem
  ) => {
    if (item.type !== 'image' || item.status !== 'completed') return;
    if (item.parentId) return;

    const image = event.currentTarget;
    if (!image.naturalWidth || !image.naturalHeight) return;

    const nextFrame = fitDimensionsToLongSide(
      image.naturalWidth,
      image.naturalHeight,
      Math.max(item.width, item.height)
    );
    const currentRatio = item.width / item.height;
    const nextRatio = nextFrame.width / nextFrame.height;
    if (Math.abs(Math.log(currentRatio / nextRatio)) < 0.015) return;

    onItemUpdate(item.id, centerFrameOnRect(item, nextFrame));
  };
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
    const editorWidth = inlineEditorSize.width || DEFAULT_INLINE_IMAGE_EDITOR_SIZE.width;
    const editorHeight = Math.max(
      inlineEditorSize.height || DEFAULT_INLINE_IMAGE_EDITOR_SIZE.height,
      INLINE_IMAGE_TOOLBAR_HEIGHT
    );
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

  const selectedQuickEditInputPosition = useMemo(() => {
    const viewportWidth = canvasViewportSize.width;

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
    const inputWidth = Math.min(560, Math.max(280, (viewportWidth || 560) - CANVAS_OVERLAY_MARGIN * 2));
    let screenLeft = imageLeft + imageWidth / 2 - inputWidth / 2;

    if (viewportWidth > 0) {
      screenLeft = clampValue(
        screenLeft,
        CANVAS_OVERLAY_MARGIN,
        Math.max(CANVAS_OVERLAY_MARGIN, viewportWidth - CANVAS_OVERLAY_MARGIN - inputWidth)
      );
    }

    const screenTop = imageTop + imageHeight + INLINE_IMAGE_EDITOR_GAP;

    return {
      left: (screenLeft - pan.x) / safeZoom,
      top: (screenTop - pan.y) / safeZoom,
      width: inputWidth,
      transform: `scale(${1 / safeZoom})`,
      transformOrigin: 'top left'
    };
  }, [canvasViewportSize.width, pan, selectedItem, zoom]);

  const selectedTextToolbarPosition = useMemo(() => {
    if (!selectedItem || selectedItem.type !== 'text') {
      return {
        left: 0,
        top: 0,
        transform: 'scale(1)',
        transformOrigin: 'top left'
      };
    }

    const safeZoom = zoom || 1;
    const viewportWidth = canvasViewportSize.width;
    const itemLeft = pan.x + selectedItem.x * safeZoom;
    const itemTop = pan.y + selectedItem.y * safeZoom;
    const toolbarWidth = TEXT_TOOLBAR_WIDTH;
    let screenLeft = itemLeft;

    if (viewportWidth > 0) {
      screenLeft = clampValue(
        screenLeft,
        CANVAS_OVERLAY_MARGIN,
        Math.max(CANVAS_OVERLAY_MARGIN, viewportWidth - CANVAS_OVERLAY_MARGIN - toolbarWidth)
      );
    }

    const screenTop = Math.max(CANVAS_OVERLAY_MARGIN, itemTop - TEXT_TOOLBAR_HEIGHT - 10);

    return {
      left: (screenLeft - pan.x) / safeZoom,
      top: (screenTop - pan.y) / safeZoom,
      transform: `scale(${1 / safeZoom})`,
      transformOrigin: 'top left'
    };
  }, [canvasViewportSize.width, pan, selectedItem, zoom]);

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
    selectedInlineEditDurationText,
    inlineEditPrompt,
    selectedInlineEditError,
    selectedItem?.originalPrompt,
    selectedItem?.optimizedPrompt,
    selectedItem?.prompt
  ]);

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
    if (canvasTool !== 'text') return;

    setQuickEditItemId(null);
    setLocalRedrawItemId(null);
    setLocalRemoverItemId(null);
    setCropItemId(null);
    setCropRect(null);
    setCropDragState(null);
    setActiveTool('select');
  }, [canvasTool]);

  useEffect(() => {
    if (!selectedItemIsQuickEditing) return;
    window.requestAnimationFrame(() => {
      quickEditInputRef.current?.focus();
      quickEditInputRef.current?.select();
    });
  }, [selectedItemIsQuickEditing, selectedItem?.id]);

  useEffect(() => {
    if (!editingTextItemId || selectedIds.includes(editingTextItemId)) return;
    setEditingTextItemId(null);
  }, [editingTextItemId, selectedIds]);

  useEffect(() => {
    if (!selectedTextIsEditing) return;

    window.requestAnimationFrame(() => {
      const editor = textEditorRef.current;
      if (!editor) return;
      editor.focus();
      const textLength = editor.value.length;
      editor.setSelectionRange(textLength, textLength);
    });
  }, [selectedTextIsEditing, selectedItem?.id]);

  useEffect(() => {
    if (inlineEditingIds.size === 0) return;

    setInlineTimerNow(Date.now());
    const timerId = window.setInterval(() => {
      setInlineTimerNow(Date.now());
    }, 1000);

    return () => window.clearInterval(timerId);
  }, [inlineEditingIds.size]);

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
    if (!selectedImageMaskData) return;

    let cancelled = false;
    loadImageElement(selectedImageMaskData)
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
  }, [
    selectedItemHasImageMaskTool,
    selectedImageMaskData,
    selectedImageMaskMode,
    selectedItem?.id,
    selectedItem?.width,
    selectedItem?.height
  ]);

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

  const getCurrentMaskDataForItem = (id: string, mode: ImageMaskMode, fallbackMaskData?: string) => {
    const isActiveMask = mode === 'remover'
      ? localRemoverItemId === id
      : localRedrawItemId === id;
    if (isActiveMask && maskCanvasRef.current) {
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

  const enterTextEditMode = (item: CanvasItem) => {
    if (item.type !== 'text') return;
    setSelectedIds([item.id]);
    resetImageToolModes();
    setEditingTextItemId(item.id);
  };

  const finishTextEditMode = (item?: CanvasItem, element?: HTMLTextAreaElement) => {
    setEditingTextItemId(null);
    if (!item || item.type !== 'text') return;

    const content = element?.value ?? item.content;
    if (!content.trim()) {
      onItemDelete(item.id);
      setSelectedIds(selectedIds.filter(id => id !== item.id));
      return;
    }

    setSelectedIds([]);
  };

  const updateSelectedTextStyle = (updates: CanvasTextStyle) => {
    if (!selectedItem || selectedItem.type !== 'text') return;
    const nextStyle = {
      ...getCanvasTextStyle(selectedItem),
      ...updates
    };
    const nextFrame = getTextContentFrame(selectedItem.content, nextStyle);
    onBeforeCanvasMutation();
    onItemUpdate(selectedItem.id, {
      textStyle: nextStyle,
      width: nextFrame.width,
      height: nextFrame.height
    });
  };

  const updateSelectedTextFontSize = (delta: number) => {
    const nextFontSize = clampValue(selectedTextStyle.fontSize + delta, MIN_TEXT_FONT_SIZE, MAX_TEXT_FONT_SIZE);
    updateSelectedTextStyle({ fontSize: nextFontSize });
  };

  const handleTextInput = (item: CanvasItem, element: HTMLTextAreaElement) => {
    const nextContent = element.value.replace(/\n\n$/g, '\n');
    const nextFrame = getTextContentFrame(nextContent, getCanvasTextStyle(item));
    const updates: Partial<CanvasItem> = {
      content: nextContent,
      width: nextFrame.width,
      height: nextFrame.height
    };
    onItemUpdate(item.id, updates);
  };

  const focusImageForQuickEdit = (item: CanvasItem) => {
    const viewportWidth = canvasViewportSize.width || containerRef.current?.clientWidth || window.innerWidth;
    const viewportHeight = canvasViewportSize.height || containerRef.current?.clientHeight || window.innerHeight;
    if (viewportWidth <= 0 || viewportHeight <= 0 || item.width <= 0 || item.height <= 0) return;

    const sideMargin = Math.max(72, Math.min(180, viewportWidth * 0.16));
    const topReserve = Math.max(88, Math.min(132, viewportHeight * 0.18));
    const bottomReserve = Math.max(132, Math.min(180, viewportHeight * 0.24));
    const availableWidth = Math.max(240, viewportWidth - sideMargin * 2);
    const availableHeight = Math.max(240, viewportHeight - topReserve - bottomReserve);
    const fitZoom = Math.min(availableWidth / item.width, availableHeight / item.height);
    const nextZoom = clampValue(fitZoom, QUICK_EDIT_FOCUS_MIN_ZOOM, QUICK_EDIT_FOCUS_MAX_ZOOM);
    const imageScreenWidth = item.width * nextZoom;
    const imageScreenHeight = item.height * nextZoom;
    const imageScreenLeft = (viewportWidth - imageScreenWidth) / 2;
    const imageScreenTop = topReserve + Math.max(0, (availableHeight - imageScreenHeight) / 2);

    onZoomChange(nextZoom, { x: viewportWidth / 2, y: viewportHeight / 2 });
    onPanChange({
      x: imageScreenLeft - item.x * nextZoom,
      y: imageScreenTop - item.y * nextZoom
    });
  };

  const toggleQuickEditMode = (item: CanvasItem) => {
    if (item.type !== 'image' || item.status !== 'completed' || !item.content) return;

    resetImageToolModes();
    clearInlineEditError(item.id);
    const isOpening = quickEditItemId !== item.id;
    setQuickEditItemId(isOpening ? item.id : null);
    if (isOpening) focusImageForQuickEdit(item);
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
    clearInlineEditError(item.id);
    setInlineEditPromptForItem(item.id, prompt);
    setQuickEditItemId(item.id);
    focusImageForQuickEdit(item);
    window.requestAnimationFrame(() => {
      quickEditInputRef.current?.focus();
      quickEditInputRef.current?.setSelectionRange(prompt.length, prompt.length);
    });
  };

  const applyBackgroundRemoval = (item: CanvasItem) => {
    resetImageToolModes();
    setQuickEditItemId(null);
    clearInlineEditError(item.id);
    void handleInlineImageRedraw(undefined, {
      item,
      prompt: BACKGROUND_REMOVAL_PROMPT,
      mode: 'background-removal'
    });
  };

  const handleImageLayerSplit = async (item: CanvasItem) => {
    if (item.type !== 'image' || item.status !== 'completed' || !item.content || inlineEditingIdsRef.current.has(item.id)) return;

    const startedAt = Date.now();
    const sourceTitle = getCanvasItemDisplayTitle(item);
    const layerGroupId = `layers-${Math.random().toString(36).slice(2, 10)}`;
    const layerRoles: LayerSplitRole[] = ['subject', 'background'];
    const createdItemIds: string[] = [];
    const unsubscribeAgentRunEvents: Array<() => void> = [];
    let imageTaskStartedAt: number | null = null;
    let planningMessageId = '';
    let splitAgentPlan = buildAgentDraftPlan({
      taskType: 'image-edit',
      mode: 'layer-subject',
      aspectRatio: 'auto',
      summary: '正在拆分图片图层...',
      displayTitle: `${sourceTitle}图层拆分`,
      displayMessage: `我将为您拆分这张${ensureImageNoun(sourceTitle)}的主体层和背景层。`,
      steps: buildLayerSplitDraftSteps()
    });

    const updateSplitPlan = (stepId: string, title: string, description: string, status: AgentPlanStep['status'], text?: string) => {
      splitAgentPlan = applyAgentRunProgressEventToPlan(splitAgentPlan, {
        stepId,
        title,
        description,
        stepType: stepId === 'identify-layer-subject' ? 'analysis' : stepId === 'optimize-layer-split' ? 'prompt' : 'edit',
        status,
        createdAt: Date.now()
      });
      onChatMessageUpdate(planningMessageId, message => ({
        ...message,
        text: text || `${title}：${description}`,
        agentPlan: splitAgentPlan
      }));
    };

    resetImageToolModes();
    clearInlineEditError(item.id);
    setInlineEditingForItem(item.id, true);
    setInlineEditingStartedAt(prev => ({ ...prev, [item.id]: startedAt }));
    onBeforeCanvasMutation();
    onChatMessage(`图层拆分 @${item.id}`, 'user');
    planningMessageId = onChatDraftMessage('识别主体：正在分析图片主要前景。', 'assistant', {
      agentPlan: splitAgentPlan
    });

    try {
      const persistedSource = await ensureCanvasImageAsset(item);
      onItemUpdate(item.id, {
        content: persistedSource.content,
        assetId: persistedSource.assetId,
        previewContent: persistedSource.previewContent,
        thumbnailContent: persistedSource.thumbnailContent
      });

      updateSplitPlan('identify-layer-subject', '识别主体', '已锁定原图并准备拆分主体层与背景层。', 'completed');
      updateSplitPlan('optimize-layer-split', '规划拆层', '正在准备主体层和背景层的编辑指令。', 'running');

      const resultMaxLongSide = Math.max(persistedSource.width, persistedSource.height);
      const resultFrame = fitDimensionsToLongSide(persistedSource.width, persistedSource.height, resultMaxLongSide);
      const nextZIndex = Math.max(0, ...items.map(item => item.zIndex || 0)) + 1;
      let virtualItems = [...items, persistedSource];
      const placeholders = layerRoles.map((role, index) => {
        const position = findRightSideCanvasPosition(virtualItems, persistedSource, resultFrame.width, resultFrame.height);
        const placeholder: CanvasItem = {
          id: Math.random().toString(36).substr(2, 9),
          type: 'image',
          content: '',
          x: position.x,
          y: position.y,
          width: resultFrame.width,
          height: resultFrame.height,
          status: 'loading',
          label: getLayerSplitLoadingLabel(role),
          zIndex: nextZIndex + index,
          parentId: persistedSource.id,
          layerGroupId,
          layerRole: role,
          prompt: getLayerSplitPrompt(role),
          originalPrompt: getLayerSplitPrompt(role),
          optimizedPrompt: '',
          layers: []
        };
        virtualItems = [...virtualItems, placeholder];
        return placeholder;
      });

      placeholders.forEach(placeholder => {
        createdItemIds.push(placeholder.id);
        onItemAdd(placeholder);
      });

      updateSplitPlan('optimize-layer-split', '规划拆层', '主体层和背景层指令已准备完成。', 'completed');
      updateSplitPlan('run-layer-split', '执行拆层', '正在并行提交两个图片编辑任务。', 'running');
      imageTaskStartedAt = Date.now();
      onImageTaskStart(imageTaskStartedAt);

      const runLayerSplitJob = async (placeholder: CanvasItem) => {
        const role = placeholder.layerRole === 'background' ? 'background' : 'subject';
        const prompt = getLayerSplitPrompt(role);
        const clientRunId = createAgentRunClientId();
        const unsubscribe = await connectAgentRunEvents(clientRunId, (event) => {
          if (event.status === 'running') {
            onChatMessageUpdate(planningMessageId, message => ({
              ...message,
              text: `${getLayerSplitLabel(role)}：${event.description || event.title}`,
              agentPlan: splitAgentPlan
            }));
          }
        });
        unsubscribeAgentRunEvents.push(unsubscribe);

        const agentRun = await createAgentRun({
          prompt,
          aspectRatio: 'auto',
          contextImageId: persistedSource.id,
          requestedEditMode: role === 'subject' ? 'layer-subject' : 'layer-background',
          images: [persistedSource],
          clientRunId
        });
        const job = agentRun.jobs[0];
        if (!job) {
          throw new Error(`${getLayerSplitLabel(role)}任务创建失败`);
        }

        onItemUpdate(placeholder.id, {
          imageJobId: job.jobId,
          imageJobStartedAt: imageTaskStartedAt || Date.now(),
          prompt: agentRun.requestPrompt || prompt,
          optimizedPrompt: job.optimizedPrompt || ''
        });

        const imageResult = await waitForImageJob(job.jobId, {
          onStatus: (jobStatus) => {
            const queueNotice = getImageJobQueueMessage(jobStatus, `${getLayerSplitLabel(role)}任务`);
            if (queueNotice) {
              onChatMessageUpdate(planningMessageId, message => ({
                ...message,
                text: queueNotice,
                agentPlan: splitAgentPlan
              }));
              onItemUpdate(placeholder.id, { label: '排队中...' });
              return;
            }

            if (jobStatus.status === 'running') {
              onItemUpdate(placeholder.id, { label: getLayerSplitLoadingLabel(role) });
            }
          }
        });
        const resultTitle = `${sourceTitle}${getLayerSplitLabel(role)}`;
        const resultDescription = buildImageResultDescription(resultTitle, 'edited');
        const optimizedPrompt = imageResult.optimizedPrompt || job.optimizedPrompt || agentRun.requestPrompt || prompt;
        const persistedResultItem = await ensureCanvasImageAsset({
          ...placeholder,
          content: imageResult.image,
          status: 'completed',
          imageJobId: undefined,
          imageJobStartedAt: undefined,
          prompt: agentRun.requestPrompt || prompt,
          originalPrompt: prompt,
          optimizedPrompt,
          label: resultTitle
        });

        onItemUpdate(placeholder.id, {
          content: persistedResultItem.content,
          assetId: persistedResultItem.assetId,
          previewContent: persistedResultItem.previewContent,
          thumbnailContent: persistedResultItem.thumbnailContent,
          status: 'completed',
          imageJobId: undefined,
          imageJobStartedAt: undefined,
          prompt: agentRun.requestPrompt || prompt,
          originalPrompt: prompt,
          optimizedPrompt,
          label: resultTitle
        });

        return {
          imageId: placeholder.id,
          modelName: getImageModelDisplayName(getApiConfig().model),
          title: resultTitle,
          description: resultDescription
        };
      };

      const settledResults = await Promise.allSettled(placeholders.map(runLayerSplitJob));
      const resultCards = settledResults
        .filter((result): result is PromiseFulfilledResult<Awaited<ReturnType<typeof runLayerSplitJob>>> => result.status === 'fulfilled')
        .map(result => result.value);
      const failedResults = settledResults.filter(result => result.status === 'rejected') as PromiseRejectedResult[];

      settledResults.forEach((result, index) => {
        if (result.status === 'rejected') {
          onItemDelete(placeholders[index].id);
        }
      });

      if (resultCards.length === 0) {
        throw new Error(failedResults[0]?.reason instanceof Error ? failedResults[0].reason.message : '图层拆分失败');
      }

      updateSplitPlan('run-layer-split', '执行拆层', '图片任务已经返回，正在保存到画布。', 'completed');
      updateSplitPlan('wait-image-job', '等待生成', '图层已经生成并保存到画布。', 'completed');
      const finalText = failedResults.length > 0
        ? `已拆分出 ${resultCards.length} 个图层，另有 ${failedResults.length} 个图层生成失败。`
        : '已为您拆分出主体层和背景层。';
      onChatMessageUpdate(planningMessageId, message => ({
        ...message,
        text: `我将为您拆分这张${ensureImageNoun(sourceTitle)}的主体层和背景层。`,
        agentPlan: undefined,
        durationMs: Date.now() - startedAt
      }));
      onChatMessage(finalText, 'assistant', {
        imageIds: resultCards.map(card => card.imageId),
        imageResultCards: resultCards,
        durationMs: Date.now() - startedAt
      });
      if (failedResults.length > 0) {
        const message = failedResults[0]?.reason instanceof Error ? failedResults[0].reason.message : '部分图层拆分失败';
        setInlineEditErrors(prev => ({ ...prev, [item.id]: message }));
      }
    } catch (error) {
      const message = error instanceof Error ? error.message : '图层拆分失败';
      console.error(error);
      createdItemIds.forEach(id => onItemDelete(id));
      setInlineEditErrors(prev => ({ ...prev, [item.id]: message }));
      onChatMessageUpdate(planningMessageId, currentMessage => ({
        ...currentMessage,
        text: `图层拆分失败：${message}`,
        agentPlan: undefined,
        durationMs: Date.now() - startedAt
      }));
      onChatMessage(`出错了，没能完成图层拆分：${message}`, 'assistant', {
        durationMs: Date.now() - startedAt
      });
    } finally {
      unsubscribeAgentRunEvents.forEach(unsubscribe => unsubscribe());
      if (imageTaskStartedAt !== null) {
        onImageTaskFinish(imageTaskStartedAt);
      }
      setInlineEditingForItem(item.id, false);
      setInlineEditingStartedAt(prev => {
        const { [item.id]: _removedStartedAt, ...nextStartedAt } = prev;
        return nextStartedAt;
      });
      setLocalRedrawItemId(null);
      setLocalRemoverItemId(null);
      setActiveTool('select');
    }
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

  const handleDownloadSelectedText = (item: CanvasItem) => {
    if (item.type !== 'text' || !item.content.trim()) return;

    const textStyle = getCanvasTextStyle(item);
    const textAnchor = textStyle.textAlign === 'center' ? 'middle' : textStyle.textAlign === 'right' ? 'end' : 'start';
    const textX = textStyle.textAlign === 'center' ? item.width / 2 : textStyle.textAlign === 'right' ? item.width : 0;
    const textLines = item.content.split('\n');
    const tspans = textLines.map((line, index) => (
      `<tspan x="${textX}" dy="${index === 0 ? 0 : textStyle.fontSize * textStyle.lineHeight}">${escapeSvgText(line)}</tspan>`
    )).join('');
    const strokeAttributes = textStyle.strokeWidth > 0
      ? ` stroke="${escapeSvgText(textStyle.strokeColor)}" stroke-width="${textStyle.strokeWidth * 2}" stroke-linejoin="round" paint-order="stroke fill"`
      : '';
    const svg = [
      `<svg xmlns="http://www.w3.org/2000/svg" width="${Math.ceil(item.width)}" height="${Math.ceil(item.height)}" viewBox="0 0 ${Math.ceil(item.width)} ${Math.ceil(item.height)}">`,
      `<text x="${textX}" y="${textStyle.strokeWidth}" text-anchor="${textAnchor}" dominant-baseline="text-before-edge" font-family="${escapeSvgText(textStyle.fontFamily)}, system-ui, sans-serif" font-size="${textStyle.fontSize}" font-weight="${textStyle.fontWeight}" font-style="${textStyle.fontStyle}" text-decoration="${textStyle.textDecoration}" fill="${escapeSvgText(textStyle.color)}"${strokeAttributes}>${tspans}</text>`,
      '</svg>'
    ].join('');
    const blob = new Blob([svg], { type: 'image/svg+xml;charset=utf-8' });
    const objectUrl = URL.createObjectURL(blob);
    triggerBrowserDownload(objectUrl, `${sanitizeDownloadFilename(item.label || item.content.slice(0, 24) || 'text-layer')}.svg`);
    window.setTimeout(() => URL.revokeObjectURL(objectUrl), 1000);
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
        label: `${getCanvasItemDisplayTitle(selectedItem)}裁剪`,
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

      if (e.key === 'Escape' && canvasTool === 'text') {
        e.preventDefault();
        onCanvasToolChange('select');
        return;
      }

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
  }, [canvasTool, onBeforeCanvasMutation, onCanvasToolChange, onItemDeleteMultiple, selectedIds, selectedItem, selectedItemCanQuickEdit]);

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
    if (isEditableTarget(e.target)) return;

    if (canvasTool === 'text') {
      e.preventDefault();
      onBeforeCanvasMutation();
      const newTextId = onAddTextAt((e.clientX - pan.x) / zoom, (e.clientY - pan.y) / zoom);
      setEditingTextItemId(newTextId);
      onCanvasToolChange('select');
      setContextMenu(null);
      return;
    }

    if (isSpacePressed || canvasTool === 'pan') {
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
          maskStrokePointsRef.current = activeTool === 'brush' ? [{ x, y }] : [];
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
          if (activeTool === 'brush') {
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
      const snapResult = getDraggedItemsSnapResult(items, selectedIds, dx, dy, zoom);
      items.filter(i => selectedIds.includes(i.id)).forEach(item => {
        onItemUpdate(item.id, { x: item.x + snapResult.dx, y: item.y + snapResult.dy });
      });
      setSnapGuides(snapResult.guides);
      setLastMousePos({ x: e.clientX, y: e.clientY });
    }
  };

  const handleMouseUp = () => {
    if (isDrawing && selectedItemHasImageMaskTool) {
      setIsDrawing(false);
      if (activeTool === 'brush') {
        fillImageMaskLassoSelection(
          maskCanvasRef.current,
          maskStrokePointsRef.current,
          brushSize,
          activeImageMaskStrokeColor,
          selectedItemIsRemover ? 'rgba(239, 68, 68, 0.28)' : 'rgba(99, 102, 241, 0.24)'
        );
      }
      maskStrokePointsRef.current = [];
      const data = maskCanvasRef.current?.toDataURL('image/png');
      if (data && selectedItem && selectedImageMaskMode) {
        onItemUpdate(selectedItem.id, getImageMaskUpdateForMode(selectedImageMaskMode, data));
      }
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
    setSnapGuides([]);
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
    if (isEditableTarget(e.target)) return;
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
    let imageTaskStartedAt: number | null = null;
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
          const textStyle = getCanvasTextStyle(item);
          ctx.font = `${textStyle.fontStyle} ${textStyle.fontWeight} ${textStyle.fontSize}px ${textStyle.fontFamily}, system-ui, sans-serif`;
          ctx.fillStyle = textStyle.color;
          ctx.strokeStyle = textStyle.strokeColor;
          ctx.lineWidth = textStyle.strokeWidth * 2;
          ctx.lineJoin = 'round';
          ctx.textAlign = textStyle.textAlign;
          ctx.textBaseline = 'top';
          const textX = item.x - selectedItem.x + (textStyle.textAlign === 'center' ? item.width / 2 : textStyle.textAlign === 'right' ? item.width : 0);
          item.content.split('\n').forEach((line, index) => {
            if (textStyle.strokeWidth > 0) {
              ctx.strokeText(line, textX, item.y - selectedItem.y + index * textStyle.fontSize * textStyle.lineHeight);
            }
            ctx.fillText(line, textX, item.y - selectedItem.y + index * textStyle.fontSize * textStyle.lineHeight);
          });
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

      const snapshotItem: CanvasItem = {
        id: `workflow-${Math.random().toString(36).substr(2, 9)}`,
        type: 'image',
        content: snapshot,
        x: selectedItem.x,
        y: selectedItem.y,
        width: selectedItem.width,
        height: selectedItem.height,
        status: 'completed',
        label: '画布快照',
        zIndex: selectedItem.zIndex,
        prompt: frameworkPrompt || '视觉逻辑生成',
        originalPrompt: frameworkPrompt || '视觉逻辑生成',
        layers: []
      };
      const persistedSnapshot = await ensureCanvasImageAsset(snapshotItem);
      const workflowPrompt = [
        '根据这张画布快照进行视觉逻辑生成。',
        '请理解画布中的图片、文字、箭头、涂鸦和布局关系，输出一张干净、写实、高画质的最终图像。',
        '严禁出现画布 UI、选中框、工具栏、边框或截图痕迹。',
        frameworkPrompt ? `用户补充要求：${frameworkPrompt}` : ''
      ].filter(Boolean).join('\n');
      const agentRun = await createAgentRun({
        prompt: workflowPrompt,
        aspectRatio: 'auto',
        contextImageId: persistedSnapshot.id,
        images: [persistedSnapshot]
      });
      const job = agentRun.jobs[0];
      if (!job) {
        throw new Error('Agent 没有创建可执行的图片任务');
      }
      imageTaskStartedAt = Date.now();
      onImageTaskStart(imageTaskStartedAt);
      const imageResult = await waitForImageJob(job.jobId, {
        onStatus: (jobStatus) => {
          const queueNotice = getImageJobQueueMessage(jobStatus, '视觉逻辑任务');
          if (!queueNotice) return;
          onItemUpdate(selectedItem.id, { label: '排队中...' });
        }
      });
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
        label: generateImageTitleFromPrompt(frameworkPrompt || imageResult.originalPrompt || '', '视觉逻辑生成'),
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
      if (imageTaskStartedAt !== null) {
        onImageTaskFinish(imageTaskStartedAt);
      }
      setIsGenerating(false);
    }
  };

  const handleInlineImageRedraw = async (
    event?: React.FormEvent,
    options?: { item?: CanvasItem; prompt?: string; mode?: 'background-removal' }
  ) => {
    event?.preventDefault();
    const targetCandidate = options?.item || selectedItem;
    const rawPrompt = (options?.prompt ?? inlineEditPrompt).trim();
    const isBackgroundRemovalMode = options?.mode === 'background-removal';
    const isRemoverMode = !isBackgroundRemovalMode && targetCandidate?.type === 'image' && localRemoverItemId === targetCandidate.id;
    if ((!rawPrompt && !isRemoverMode && !isBackgroundRemovalMode) || !targetCandidate || targetCandidate.type !== 'image' || inlineEditingIdsRef.current.has(targetCandidate.id)) return;

    const targetItem = targetCandidate;
    const prompt = isRemoverMode
      ? rawPrompt
        ? `${REMOVER_PROMPT} 用户补充要求：${rawPrompt}`
        : REMOVER_PROMPT
      : isBackgroundRemovalMode
        ? rawPrompt || BACKGROUND_REMOVAL_PROMPT
      : rawPrompt;
    const userMessage = isBackgroundRemovalMode
      ? `去背景 @${targetItem.id}`
      : isRemoverMode
      ? rawPrompt
        ? `删除 @${targetItem.id} 圈选区域：${rawPrompt}`
        : `删除 @${targetItem.id} 圈选区域`
      : `编辑 @${targetItem.id}：${rawPrompt}`;
    const localMaskMode: ImageMaskMode | null = !isBackgroundRemovalMode && localRemoverItemId === targetItem.id
      ? 'remover'
      : !isBackgroundRemovalMode && localRedrawItemId === targetItem.id ? 'local-redraw' : null;
    const useLocalMask = !!localMaskMode;
    const startedAt = Date.now();
    let imageTaskStartedAt: number | null = null;
    let resultItemId: string | null = null;
    let unsubscribeAgentRunEvents: () => void = () => {};
    const agentRunClientId = createAgentRunClientId();
    const inlineAgentMode: AgentPlan['mode'] = isBackgroundRemovalMode
      ? 'background-removal'
      : isRemoverMode ? 'remover' : 'edit';
    const inlineAgentAspectRatio = isRemoverMode || isBackgroundRemovalMode ? 'auto' : inlineEditAspectRatio;
    let inlineAgentPlan = buildAgentDraftPlan({
      taskType: 'image-edit',
      mode: inlineAgentMode,
      aspectRatio: inlineAgentAspectRatio
    });
    setInlineEditingForItem(targetItem.id, true);
    setInlineEditingStartedAt(prev => ({ ...prev, [targetItem.id]: startedAt }));
    clearInlineEditError(targetItem.id);
    onChatMessage(userMessage, 'user');
    const planningMessageId = onChatDraftMessage('我先识别这次图片快捷编辑的意图。', 'assistant', {
      agentPlan: inlineAgentPlan
    });
    const syncInlineAgentPlanMessage = (nextPlan: AgentPlan, nextText = nextPlan.summary) => {
      inlineAgentPlan = nextPlan;
      onChatMessageUpdate(planningMessageId, message => ({
        ...message,
        text: nextText,
        agentPlan: nextPlan
      }));
    };
    const collapseInlineAgentPlanMessage = (nextText: string) => {
      onChatMessageUpdate(planningMessageId, message => ({
        ...message,
        text: nextText,
        agentPlan: undefined
      }));
    };

    try {
      const currentMaskData = localMaskMode
        ? getCurrentMaskDataForItem(
          targetItem.id,
          localMaskMode,
          getImageMaskDataForMode(targetItem, localMaskMode)
        )
        : undefined;

      if (useLocalMask && !currentMaskData) {
        throw new Error(isRemoverMode ? '请先用画笔涂抹需要删除的物体' : '请先用画笔涂抹需要局部重绘的区域');
      }

      const agentPrompt = useLocalMask
        ? isRemoverMode
          ? `${prompt}。只删除或修复用户用蒙版涂抹的局部区域，未被蒙版覆盖的区域必须保持原图不变。`
          : `${prompt}。只修改用户用蒙版涂抹的局部区域，未被蒙版覆盖的区域必须保持原图不变。`
        : prompt;
      const agentCandidateImages = [
        targetItem,
        ...resolveMentionedImageReferences(agentPrompt, items).filter(item => item.id !== targetItem.id)
      ];
      const persistentAgentImages = await Promise.all(
        agentCandidateImages.map(item => ensureCanvasImageAsset(item))
      );
      for (const item of persistentAgentImages) {
        onItemUpdate(item.id, {
          content: item.content,
          assetId: item.assetId,
          previewContent: item.previewContent,
          thumbnailContent: item.thumbnailContent
        });
      }

      let maskDataUrl: string | null | undefined;
      if (localMaskMode) {
        const persistedTargetForMask = persistentAgentImages.find(item => item.id === targetItem.id) || targetItem;
        maskDataUrl = await createTransparentEditMask(
          currentMaskData!,
          persistedTargetForMask.content,
          targetItem.width,
          targetItem.height,
          isRemoverMode
            ? { outlineDilationRadius: 6, editableDilationRadius: 5 }
            : undefined
        );
        if (!maskDataUrl) {
          throw new Error(isRemoverMode ? '请先用画笔涂抹需要删除的物体' : '请先用画笔涂抹需要局部重绘的区域');
        }
        onItemUpdate(targetItem.id, getImageMaskUpdateForMode(localMaskMode, currentMaskData!));
      }

      unsubscribeAgentRunEvents = await connectAgentRunEvents(agentRunClientId, (event) => {
        const nextPlan = applyAgentRunProgressEventToPlan(inlineAgentPlan, event);
        syncInlineAgentPlanMessage(nextPlan, event.description ? `${event.title}：${event.description}` : nextPlan.summary);
      });

      const agentRun = await createAgentRun({
        prompt: agentPrompt,
        aspectRatio: inlineAgentAspectRatio,
        contextImageId: targetItem.id,
        requestedEditMode: localMaskMode || undefined,
        images: persistentAgentImages,
        maskDataUrl: maskDataUrl || undefined,
        clientRunId: agentRunClientId
      });
      const job = agentRun.jobs[0];
      if (!job) {
        throw new Error('Agent 没有创建可执行的图片任务');
      }
      inlineAgentPlan = {
        ...agentRun.plan,
        steps: agentRun.plan.steps.length > 0 ? agentRun.plan.steps : inlineAgentPlan.steps
      };
      const waitPlan = applyAgentRunProgressEventToPlan(inlineAgentPlan, {
        stepId: 'wait-image-job',
        title: '等待生成',
        description: '图片任务已提交，正在等待上游返回结果。',
        stepType: 'edit',
        status: 'running',
        createdAt: Date.now()
      });
      syncInlineAgentPlanMessage(waitPlan, '等待生成：图片任务已提交，正在等待上游返回结果。');
      imageTaskStartedAt = Date.now();
      onImageTaskStart(imageTaskStartedAt);
      const persistentById = new Map(persistentAgentImages.map(item => [item.id, item]));
      const editBaseItem = persistentById.get(agentRun.baseImageId) || persistentById.get(targetItem.id) || targetItem;
      const persistentReferenceImages = agentRun.referenceImageIds
        .map(id => persistentById.get(id))
        .filter((item): item is CanvasItem => !!item);
      const nextZIndex = Math.max(0, ...items.map(item => item.zIndex || 0)) + 1;
      const newId = Math.random().toString(36).substr(2, 9);
      const resultMaxLongSide = Math.max(editBaseItem.width, editBaseItem.height);
      const shouldPreserveSourceFrame = isRemoverMode || isBackgroundRemovalMode || inlineEditAspectRatio === 'auto';
      const resultFrame = shouldPreserveSourceFrame
        ? fitDimensionsToLongSide(editBaseItem.width, editBaseItem.height, resultMaxLongSide)
        : getAspectRatioFrame(
          inlineEditAspectRatio,
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
        label: isBackgroundRemovalMode ? 'AI 去背景中...' : isRemoverMode ? 'AI 删除中...' : 'AI 重绘中...',
        zIndex: nextZIndex,
        parentId: editBaseItem.id,
        prompt: agentRun.requestPrompt || agentPrompt,
        originalPrompt: rawPrompt || prompt,
        optimizedPrompt: job.optimizedPrompt || '',
        imageJobId: job.jobId,
        imageJobStartedAt: imageTaskStartedAt,
        layers: []
      };

      onItemAdd(resultItem);
      resultItemId = newId;

      let queuedInlineJob = false;
      const imageResult: ImageGenerationResult = await waitForImageJob(job.jobId, {
        onStatus: (jobStatus) => {
          const queueNotice = buildInlineImageJobQueueNotice(jobStatus);
          if (queueNotice) {
            queuedInlineJob = true;
            syncInlineAgentPlanMessage(inlineAgentPlan, queueNotice);
            onItemUpdate(newId, { label: '排队中...' });
            return;
          }

          if (jobStatus.status === 'running' && queuedInlineJob) {
            syncInlineAgentPlanMessage(inlineAgentPlan, '等待生成：图片编辑已开始执行。');
            onItemUpdate(newId, { label: isBackgroundRemovalMode ? 'AI 去背景中...' : isRemoverMode ? 'AI 删除中...' : 'AI 重绘中...' });
          }
        }
      });
      const completedWaitPlan = applyAgentRunProgressEventToPlan(inlineAgentPlan, {
        stepId: 'wait-image-job',
        title: '等待生成',
        description: '图片已经生成，正在保存到画布。',
        stepType: 'edit',
        status: 'completed',
        createdAt: Date.now()
      });
      syncInlineAgentPlanMessage(completedWaitPlan, '等待生成：图片已经生成，正在保存到画布。');
      const result = imageResult.image;
      const rawOptimizedPrompt = imageResult.optimizedPrompt || job.optimizedPrompt || imageResult.originalPrompt || agentRun.requestPrompt || agentPrompt;
      const optimizedPrompt = normalizeOptimizedPromptImageReferences(
        rawOptimizedPrompt,
        editBaseItem,
        persistentReferenceImages,
        items
      );
      const resultTitle = agentRun.displayTitle || agentRun.plan.displayTitle || generateImageTitleFromPrompt(
        rawPrompt || prompt || optimizedPrompt || agentRun.requestPrompt || agentPrompt,
        isBackgroundRemovalMode ? '去背景' : isRemoverMode ? '删除物体' : '编辑结果'
      );
      const resultDescription = buildImageResultDescription(resultTitle, 'edited');
      const persistedResultItem = await ensureCanvasImageAsset({
        ...resultItem,
        content: result,
        status: 'completed',
        imageJobId: undefined,
        imageJobStartedAt: undefined,
        originalPrompt: rawPrompt || prompt,
        optimizedPrompt,
        label: resultTitle
      });
      onItemUpdate(newId, {
        content: persistedResultItem.content,
        assetId: persistedResultItem.assetId,
        previewContent: persistedResultItem.previewContent,
        thumbnailContent: persistedResultItem.thumbnailContent,
        status: 'completed',
        imageJobId: undefined,
        imageJobStartedAt: undefined,
        originalPrompt: rawPrompt || prompt,
        optimizedPrompt,
        label: resultTitle
      });
      onChatMessage(resultDescription, 'assistant', {
        imageIds: [newId],
        imageResultCards: [{
          imageId: newId,
          modelName: getImageModelDisplayName(getApiConfig().model),
          title: resultTitle,
          description: resultDescription
        }],
        durationMs: Date.now() - startedAt
      });
      const rawExecutionMessage = agentRun.displayMessage || agentRun.plan.displayMessage || '';
      const executionTitle = ensureImageNoun(resultTitle);
      const fallbackExecutionMessage = isBackgroundRemovalMode
        ? `我将为您去除这张${executionTitle}的背景。`
        : isRemoverMode
          ? `我将为您删除圈选区域，并生成新的${executionTitle}。`
          : `我将为您编辑这张${executionTitle}。`;
      collapseInlineAgentPlanMessage(
        rawExecutionMessage && !isProcessDisclosureMessage(rawExecutionMessage)
          ? rawExecutionMessage
          : fallbackExecutionMessage
      );
      setInlineEditPromptForItem(targetItem.id, '');
      setQuickEditItemId(null);
      if (isBackgroundRemovalMode) {
        setLocalRedrawItemId(null);
        setLocalRemoverItemId(null);
        setActiveTool('select');
      } else if (isRemoverMode) {
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
      const errorPlan = applyAgentRunProgressEventToPlan(inlineAgentPlan, {
        stepId: 'inline-edit-error',
        title: '执行失败',
        description: message,
        stepType: 'edit',
        status: 'error',
        createdAt: Date.now()
      });
      syncInlineAgentPlanMessage(errorPlan, `执行失败：${message}`);
      if (resultItemId) {
        onItemDelete(resultItemId);
      }
      if (isBackgroundRemovalMode) {
        setSelectedIds([targetItem.id]);
        setLocalRedrawItemId(null);
        setLocalRemoverItemId(null);
        setActiveTool('select');
      } else if (isRemoverMode) {
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
      onChatMessage(`出错了，没能完成单图编辑：${message}`, 'assistant', {
        durationMs: Date.now() - startedAt
      });
    } finally {
      unsubscribeAgentRunEvents();
      if (imageTaskStartedAt !== null) {
        onImageTaskFinish(imageTaskStartedAt);
      }
      setInlineEditingForItem(targetItem.id, false);
      setInlineEditingStartedAt(prev => {
        const { [targetItem.id]: _removedStartedAt, ...nextStartedAt } = prev;
        return nextStartedAt;
      });
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
    const activeMaskMode = isActiveMaskItem ? selectedImageMaskMode : null;
    const legacyMaskData = !item.redrawMaskData && !item.removerMaskData ? item.maskData : undefined;
    const redrawMaskData = item.redrawMaskData || (activeMaskMode === 'remover' ? undefined : legacyMaskData);
    const removerMaskData = item.removerMaskData || (activeMaskMode === 'remover' ? legacyMaskData : undefined);
    const shouldRenderRedrawMask = !!redrawMaskData && activeMaskMode !== 'local-redraw';
    const shouldRenderRemoverMask = !!removerMaskData && activeMaskMode !== 'remover';

    if (!isActiveMaskItem && !shouldRenderRedrawMask && !shouldRenderRemoverMask) return null;

    return (
      <>
        {shouldRenderRedrawMask && (
          <img src={redrawMaskData} className="absolute inset-0 z-[60] h-full w-full pointer-events-none" />
        )}
        {shouldRenderRemoverMask && (
          <img src={removerMaskData} className="absolute inset-0 z-[61] h-full w-full pointer-events-none" />
        )}
        {isActiveMaskItem && (
          <canvas
            ref={maskCanvasRef}
            width={getCanvasDimension(item.width)}
            height={getCanvasDimension(item.height)}
            className={`absolute inset-0 z-[62] h-full w-full ${
              activeTool === 'select' ? 'pointer-events-none' : 'cursor-crosshair pointer-events-auto'
            }`}
          />
        )}
      </>
    );
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

  const renderTextToolbar = () => {
    if (canvasTool !== 'select' || !selectedItem || selectedItem.type !== 'text' || selectedIds.length !== 1) return null;

    const currentFontStyle = TEXT_FONT_STYLES.find(style => (
      style.fontWeight === selectedTextStyle.fontWeight && style.fontStyle === selectedTextStyle.fontStyle
    )) || TEXT_FONT_STYLES[0];
    const currentAlignIcon = selectedTextStyle.textAlign === 'center'
      ? <AlignCenter size={16} />
      : selectedTextStyle.textAlign === 'right'
        ? <AlignRight size={16} />
        : <AlignLeft size={16} />;
    const strokeWidthIndex = TEXT_STROKE_WIDTHS.indexOf(selectedTextStyle.strokeWidth);
    const nextStrokeWidth = TEXT_STROKE_WIDTHS[(strokeWidthIndex + 1) % TEXT_STROKE_WIDTHS.length];
    const normalizedFontSearch = textFontSearch.trim().toLowerCase();
    const visibleFontFamilies = TEXT_FONT_FAMILIES.filter(fontFamily => (
      !normalizedFontSearch || fontFamily.toLowerCase().includes(normalizedFontSearch)
    ));
    const visibleFontSizes = Array.from(new Set([...TEXT_FONT_SIZES, selectedTextStyle.fontSize]))
      .sort((left, right) => left - right);
    const toolbarButtonClass = 'flex h-11 items-center justify-center border-r border-zinc-100 text-zinc-700 transition-colors hover:bg-zinc-50 active:bg-zinc-100';
    const popoverClass = 'absolute left-0 top-[calc(100%+8px)] z-[2200100] rounded-[18px] border border-zinc-200 bg-white p-2 text-zinc-800 shadow-[0_18px_60px_-28px_rgba(0,0,0,0.55)] ring-1 ring-black/5';
    const toggleTextPopover = (popover: typeof activeTextPopover) => {
      setActiveTextPopover(previous => previous === popover ? null : popover);
    };
    const stopTextToolbarWheel = (event: React.WheelEvent) => {
      event.stopPropagation();
    };
    const applyTextColor = (key: 'color' | 'strokeColor', value: string) => {
      if (!/^#[0-9a-f]{6}$/i.test(value)) return;
      updateSelectedTextStyle(key === 'strokeColor'
        ? { strokeColor: value, strokeWidth: selectedTextStyle.strokeWidth > 0 ? selectedTextStyle.strokeWidth : 2 }
        : { color: value }
      );
    };
    const renderColorPopover = (key: 'color' | 'strokeColor') => {
      const currentColor = key === 'strokeColor' ? selectedTextStyle.strokeColor : selectedTextStyle.color;
      return (
        <div className={`${popoverClass} w-56`} data-text-toolbar-interactive="true">
          <div className="mb-2 grid grid-cols-6 gap-1.5">
            {TEXT_COLOR_PALETTE.map(color => (
              <button
                key={`${key}-${color}`}
                type="button"
                onClick={() => applyTextColor(key, color)}
                className={`h-7 rounded-full border transition-transform hover:scale-105 active:scale-95 ${
                  color.toLowerCase() === currentColor.toLowerCase() ? 'border-zinc-950 ring-2 ring-zinc-200' : 'border-zinc-200'
                }`}
                style={{ backgroundColor: color }}
                title={color}
                aria-label={`选择颜色 ${color}`}
              />
            ))}
          </div>
          <input
            type="text"
            value={currentColor}
            onChange={(event) => applyTextColor(key, event.target.value.trim())}
            className="h-9 w-full rounded-xl border border-zinc-200 bg-zinc-50 px-3 text-xs font-black uppercase text-zinc-700 outline-none focus:border-zinc-400 focus:bg-white"
            aria-label={key === 'strokeColor' ? '描边颜色 HEX' : '文字颜色 HEX'}
          />
        </div>
      );
    };

    return (
      <div
        className="absolute z-[2200000] flex h-11 items-center overflow-visible rounded-[16px] border border-zinc-200 bg-white/95 text-zinc-700 shadow-[0_10px_30px_-24px_rgba(0,0,0,0.55)] backdrop-blur-xl"
        style={selectedTextToolbarPosition}
        onMouseDown={(event) => {
          event.stopPropagation();
          const target = event.target;
          if (target instanceof HTMLElement && target.closest('select,input,[data-text-toolbar-interactive="true"]')) return;
          event.preventDefault();
        }}
        onWheel={stopTextToolbarWheel}
      >
        <div className="relative">
          <button
            type="button"
            onClick={() => toggleTextPopover('fill')}
            className={`${toolbarButtonClass} w-11`}
            title="文字颜色"
          >
            <span className="h-5 w-5 rounded-full border border-zinc-200" style={{ backgroundColor: selectedTextStyle.color }} />
          </button>
          {activeTextPopover === 'fill' && renderColorPopover('color')}
        </div>

        <div className="relative">
          <button
            type="button"
            onClick={() => toggleTextPopover('stroke')}
            className={`${toolbarButtonClass} w-11`}
            title="描边颜色"
          >
            <span className="flex h-5 w-5 items-center justify-center rounded-full border border-zinc-200" style={{ backgroundColor: selectedTextStyle.strokeColor }}>
              <span className="h-2.5 w-2.5 rounded-full bg-white" />
            </span>
          </button>
          {activeTextPopover === 'stroke' && renderColorPopover('strokeColor')}
        </div>

        <div className="relative">
          <button
            type="button"
            onClick={() => toggleTextPopover('font')}
            className={`${toolbarButtonClass} w-36 justify-between gap-2 px-3 text-sm font-semibold`}
            title="字体"
          >
            <span className="truncate" style={{ fontFamily: `${selectedTextStyle.fontFamily}, system-ui, sans-serif` }}>
              {selectedTextStyle.fontFamily}
            </span>
            <ChevronDown size={13} className="shrink-0 text-zinc-500" />
          </button>
          {activeTextPopover === 'font' && (
            <div className={`${popoverClass} w-64`} data-text-toolbar-interactive="true">
              <input
                type="text"
                value={textFontSearch}
                onChange={(event) => setTextFontSearch(event.target.value)}
                placeholder="搜索字体"
                className="mb-2 h-9 w-full rounded-xl border-0 bg-zinc-100 px-3 text-sm font-medium text-zinc-800 outline-none placeholder:text-zinc-400 focus:bg-zinc-50"
              />
              <div className="mb-2 flex items-center justify-between px-2 text-[11px] font-black text-zinc-500">
                <span>全部字体</span>
                <span>{visibleFontFamilies.length}</span>
              </div>
              <div className="max-h-72 overflow-y-auto pr-1 scrollbar-thin scrollbar-thumb-zinc-300 scrollbar-track-transparent">
                {visibleFontFamilies.length > 0 ? visibleFontFamilies.map(fontFamily => (
                  <button
                    key={fontFamily}
                    type="button"
                    onClick={() => {
                      updateSelectedTextStyle({ fontFamily });
                      setActiveTextPopover(null);
                      setTextFontSearch('');
                    }}
                    className={`mb-1 flex h-10 w-full items-center rounded-xl px-3 text-left text-lg transition-colors hover:bg-zinc-100 ${
                      fontFamily === selectedTextStyle.fontFamily ? 'bg-zinc-100 font-black text-zinc-950' : 'text-zinc-800'
                    }`}
                    style={{ fontFamily: `${fontFamily}, system-ui, sans-serif` }}
                  >
                    <span className="truncate">{fontFamily}</span>
                  </button>
                )) : (
                  <div className="px-3 py-8 text-center text-sm font-semibold text-zinc-400">没有匹配的字体</div>
                )}
              </div>
            </div>
          )}
        </div>

        <div className="relative">
          <button
            type="button"
            onClick={() => toggleTextPopover('style')}
            className={`${toolbarButtonClass} w-28 justify-between gap-2 px-3 text-sm font-medium`}
            title="字体样式"
          >
            <span className="truncate">{currentFontStyle.label}</span>
            <ChevronDown size={13} className="shrink-0 text-zinc-500" />
          </button>
          {activeTextPopover === 'style' && (
            <div className={`${popoverClass} w-40`} data-text-toolbar-interactive="true">
              {TEXT_FONT_STYLES.map(style => {
                const active = style.fontWeight === selectedTextStyle.fontWeight && style.fontStyle === selectedTextStyle.fontStyle;
                return (
                  <button
                    key={style.label}
                    type="button"
                    onClick={() => {
                      updateSelectedTextStyle({ fontWeight: style.fontWeight, fontStyle: style.fontStyle });
                      setActiveTextPopover(null);
                    }}
                    className={`mb-1 flex h-9 w-full items-center justify-between rounded-xl px-3 text-left text-sm transition-colors hover:bg-zinc-100 ${
                      active ? 'bg-zinc-100 text-zinc-950' : 'text-zinc-700'
                    }`}
                    style={{ fontWeight: style.fontWeight, fontStyle: style.fontStyle }}
                  >
                    <span>{style.label}</span>
                    {active && <Check size={14} />}
                  </button>
                );
              })}
            </div>
          )}
        </div>

        <div className="relative">
          <button
            type="button"
            onClick={() => toggleTextPopover('size')}
            className={`${toolbarButtonClass} w-20 gap-2 px-3 text-sm font-medium`}
            title="字号"
          >
            <span className="min-w-8 text-left">{selectedTextStyle.fontSize}</span>
            <ChevronDown size={13} className="text-zinc-500" />
          </button>
          {activeTextPopover === 'size' && (
            <div className={`${popoverClass} w-24`} data-text-toolbar-interactive="true">
              {visibleFontSizes.map(fontSize => (
                <button
                  key={fontSize}
                  type="button"
                  onClick={() => {
                    updateSelectedTextStyle({ fontSize: clampValue(fontSize, MIN_TEXT_FONT_SIZE, MAX_TEXT_FONT_SIZE) });
                    setActiveTextPopover(null);
                  }}
                  className={`mb-1 flex h-9 w-full items-center justify-between rounded-xl px-3 text-sm font-semibold transition-colors hover:bg-zinc-100 ${
                    fontSize === selectedTextStyle.fontSize ? 'bg-zinc-100 text-zinc-950' : 'text-zinc-700'
                  }`}
                >
                  <span>{fontSize}</span>
                  {fontSize === selectedTextStyle.fontSize && <Check size={14} />}
                </button>
              ))}
            </div>
          )}
        </div>

        <div className="relative border-r border-zinc-100">
          <select
            value={selectedTextStyle.textAlign}
            onChange={(event) => updateSelectedTextStyle({ textAlign: event.target.value as Required<CanvasTextStyle>['textAlign'] })}
            className="absolute inset-0 h-full w-full cursor-pointer opacity-0"
            aria-label="文字对齐"
          >
            <option value="left">左对齐</option>
            <option value="center">居中</option>
            <option value="right">右对齐</option>
          </select>
          <div className="flex h-11 w-14 items-center justify-center gap-1 text-zinc-700">
            {currentAlignIcon}
            <ChevronDown size={12} className="text-zinc-500" />
          </div>
        </div>

        <button
          type="button"
          onClick={() => updateSelectedTextStyle({ strokeWidth: nextStrokeWidth })}
          className="flex h-11 w-11 items-center justify-center border-r border-zinc-100 text-zinc-700 transition-colors hover:bg-zinc-50"
          title={`描边粗细：${selectedTextStyle.strokeWidth}px`}
        >
          <SlidersHorizontal size={16} />
        </button>

        <button
          type="button"
          onClick={() => handleDownloadSelectedText(selectedItem)}
          disabled={!selectedItem.content.trim()}
          className="flex h-11 w-12 items-center justify-center text-zinc-700 transition-colors hover:bg-zinc-50 disabled:cursor-not-allowed disabled:opacity-35"
          title="下载文字图层"
        >
          <Download size={16} />
        </button>
      </div>
    );
  };

  const renderTextLayer = (item: CanvasItem) => {
    const textStyle = getCanvasTextStyle(item);
    const textCss = getCanvasTextCss(textStyle);
    const isEditingText = editingTextItemId === item.id;
    const displayText = item.content.trim() ? item.content : TEXT_LAYER_PLACEHOLDER;

    if (isEditingText) {
      return (
        <textarea
          ref={selectedIds.length === 1 && selectedIds[0] === item.id ? textEditorRef : undefined}
          value={item.content}
          onChange={(event) => handleTextInput(item, event.currentTarget)}
          onBlur={(event) => finishTextEditMode(item, event.currentTarget)}
          onKeyDown={(event) => {
            if (event.key === 'Escape' || ((event.metaKey || event.ctrlKey) && event.key === 'Enter')) {
              event.preventDefault();
              event.currentTarget.blur();
            }
          }}
          onMouseDown={(event) => event.stopPropagation()}
          className="h-full w-full resize-none cursor-text border-0 bg-transparent p-0 outline-none"
          style={{
            ...textCss,
            overflow: 'hidden',
            whiteSpace: 'pre',
            overflowWrap: 'normal'
          }}
        />
      );
    }

    return (
      <div
        className={`h-full w-full cursor-move ${item.content.trim() ? '' : 'text-zinc-300'}`}
        style={{
          ...textCss,
          color: item.content.trim() ? textCss.color : '#a1a1aa'
        }}
        onDoubleClick={(event) => {
          event.stopPropagation();
          enterTextEditMode(item);
        }}
      >
        {item.content.trim() ? displayText : ''}
      </div>
    );
  };

  return (
    <div 
      ref={containerRef}
      className={`flex-1 relative overflow-hidden canvas-grid bg-[#fcfcfc] select-none ${
        canvasTool === 'text'
          ? 'cursor-crosshair'
          : isSpacePressed || canvasTool === 'pan'
            ? 'cursor-grab active:cursor-grabbing'
            : 'cursor-default'
      }`}
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
                : item.type === 'text'
                  ? `rounded-[2px] transition-[outline,background-color] ${
                    selectedIds.includes(item.id) && item.content.trim()
                      ? 'outline outline-1 outline-blue-500'
                      : 'outline outline-1 outline-transparent'
                  }`
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
            <div className={`relative h-full w-full overflow-hidden ${item.type === 'image' || item.type === 'text' ? '' : 'rounded-[14px]'}`}>
              {item.type === 'text' ? (
                renderTextLayer(item)
              ) : item.type === 'workflow' ? (
                <div className="w-full h-full bg-white/30" />
              ) : (() => {
                const imageSrc = getCanvasImageSrc(item, zoom);
                if (item.status === 'error') {
                  return (
                    <div className="flex h-full w-full flex-col items-center justify-center gap-2 border border-red-200 bg-red-50 px-4 text-center">
                      <div className="text-sm font-bold text-red-600">生成失败</div>
                      <div className="max-w-full break-words text-[11px] leading-relaxed text-red-500">
                        {item.label || '图片生成失败，请重新尝试'}
                      </div>
                    </div>
                  );
                }
                return imageSrc ? (
                  <img
                    src={imageSrc}
                    onLoad={(event) => handleCanvasImageLoad(event, item)}
                    className="block w-full h-full object-contain pointer-events-none"
                  />
                ) : item.status === 'loading' ? (
                  <div className="h-full w-full bg-zinc-50" />
                ) : (
                  <div className="flex h-full w-full items-center justify-center bg-gray-50 text-xs font-bold text-gray-300">
                    等待图片生成
                  </div>
                );
              })()}
              {item.type === 'image' && item.status === 'loading' && (
                <ImageGenerationSkeleton hasPreview={!!getCanvasImageSrc(item, zoom)} />
              )}
              {renderWorkflowDrawingLayer(item)}
              {renderImageMaskLayer(item)}
              {renderCropOverlay(item)}
            </div>

            {item.type !== 'image' && item.type !== 'text' && selectedIds.length === 1 && selectedIds[0] === item.id && (
              (['n', 's', 'e', 'w', 'ne', 'nw', 'se', 'sw'] as ResizeDirection[]).map(dir => renderResizeHandle(item.id, dir))
            )}
          </div>
        ))}

        {snapGuides.map((guide, index) => (
          <div
            key={`${guide.axis}-${guide.position}-${index}`}
            className="pointer-events-none absolute z-[1900000] bg-indigo-500"
            style={guide.axis === 'x' ? {
              left: guide.position,
              top: guide.spanStart,
              width: 1 / Math.max(0.01, zoom),
              height: Math.max(1, guide.spanEnd - guide.spanStart)
            } : {
              left: guide.spanStart,
              top: guide.position,
              width: Math.max(1, guide.spanEnd - guide.spanStart),
              height: 1 / Math.max(0.01, zoom)
            }}
          />
        ))}

        {renderTextToolbar()}

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

        {canvasTool === 'select' && selectedItem?.type === 'image' && selectedIds.length === 1 && !isDraggingSelectedImage && (
          <div
            className="absolute z-[2000000] pointer-events-auto"
            style={{
              left: selectedImageToolbarPosition.left,
              top: selectedImageToolbarPosition.top
            }}
          >
            <div
              className="flex flex-col items-center gap-2 animate-in fade-in zoom-in-95"
              style={{
                transform: selectedImageToolbarPosition.transform,
                transformOrigin: selectedImageToolbarPosition.transformOrigin
              }}
              onMouseDown={(event) => event.stopPropagation()}
            >
            <div ref={imageToolbarRef} className="relative h-11 w-max overflow-hidden rounded-[14px] border border-zinc-200 bg-white pr-11 shadow-[0_14px_40px_-26px_rgba(0,0,0,0.5)]">
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
                  onClick={() => applyBackgroundRemoval(selectedItem)}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-zinc-700 transition-colors hover:bg-zinc-50 hover:text-zinc-950 disabled:opacity-35"
                  title="一键去除背景并保留主体"
                >
                  <Eraser size={15} />去背景
                </button>
                <button
                  type="button"
                  onClick={() => handleImageLayerSplit(selectedItem)}
                  disabled={selectedItemIsInlineEditing || selectedItem.status === 'loading'}
                  className="flex h-11 shrink-0 items-center gap-1.5 border-r border-zinc-100 px-3 text-xs font-bold text-zinc-700 transition-colors hover:bg-zinc-50 hover:text-zinc-950 disabled:opacity-35"
                  title="拆分为主体层和背景层两个独立图片节点"
                >
                  <Copy size={15} />分层
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
                      onClick={() => {
                        if (selectedImageMaskMode) {
                          onItemUpdate(selectedItem.id, getImageMaskUpdateForMode(selectedImageMaskMode, undefined));
                        }
                      }}
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
            {selectedInlineEditError && (
              <div className="max-w-[520px] rounded-xl bg-red-50 border border-red-100 px-3 py-2 text-[11px] font-bold text-red-500 shadow-lg">
                {selectedInlineEditError}
              </div>
            )}
            </div>
          </div>
        )}

        {canvasTool === 'select' && selectedItem?.type === 'image' && selectedIds.length === 1 && selectedItemIsQuickEditing && !isDraggingSelectedImage && (
          <div
            className="absolute z-[2000000] pointer-events-auto"
            style={{
              left: selectedQuickEditInputPosition.left,
              top: selectedQuickEditInputPosition.top
            }}
          >
            <div
              className="space-y-1.5 animate-in fade-in zoom-in-95"
              style={{
                width: selectedQuickEditInputPosition.width,
                transform: selectedQuickEditInputPosition.transform,
                transformOrigin: selectedQuickEditInputPosition.transformOrigin
              }}
              onMouseDown={(event) => event.stopPropagation()}
            >
              <form
                onSubmit={(event) => handleInlineImageRedraw(event)}
                className="flex items-center gap-2 rounded-[18px] border border-zinc-200 bg-white p-1.5 shadow-[0_16px_44px_-30px_rgba(0,0,0,0.45)]"
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
                  <span className="hidden shrink-0 text-[10px] font-black uppercase tracking-widest text-zinc-300 sm:block">
                    {selectedItemIsInlineEditing && selectedInlineEditDurationText
                      ? `已执行 ${selectedInlineEditDurationText}`
                      : 'Esc 关闭'}
                  </span>
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
