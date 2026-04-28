import React, { useEffect, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import type { ChatMessage, CanvasItem, ChatImageResultCard, ImageAspectRatio } from '../types';
import { Download, Eye, Loader2, Send, RotateCcw, RotateCw, ZoomIn, ZoomOut, X } from 'lucide-react';
import { IMAGE_ASPECT_RATIO_OPTIONS } from '../services/imageSizing';
import { getImagePreviewFitStyle, getLargestCanvasImageSrc, getOriginalImageSrc, getThumbnailImageSrc, hasUsableImageSource } from '../services/imageSources';
import { formatExecutionDuration } from '../services/taskTiming';
import { getImageModelDisplayName } from '../services/config';
import {
  getAgentPlanCurrentProgressText
} from '../services/agentPlanner';
import {
  getImageReferenceDisplayText,
  insertImageMention,
  resolveMentionedImageReferences,
  tokenizeImageReferenceText
} from '../services/imageReferences';
import { buildImageResultDescription, getCanvasItemDisplayTitle } from '../services/imageTitle';
import ImageMentionEditor from './ImageMentionEditor';
import LivartLogo from './LivartLogo';

const IMAGE_VIEWER_MIN_ZOOM = 1;
const IMAGE_VIEWER_MAX_ZOOM = 10;
const IMAGE_VIEWER_ZOOM_STEP = 1;

type AssistantAnswerBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'numbered'; items: string[] }
  | { type: 'bullets'; items: string[] };

interface SidebarProps {
  messages: ChatMessage[];
  isThinking: boolean;
  activeTaskStartedAt?: number | null;
  activeTaskCount?: number;
  onSendMessage: (text: string, aspectRatio: ImageAspectRatio) => void;
  contextImage: CanvasItem | null;
  promptSeed?: { id: string; imageId: string; prompt?: string } | null;
  inputResetKey?: number;
  imageItems: CanvasItem[];
  onSelectContextImage: (item: CanvasItem) => void;
  onClearContextImage: () => void;
  onNavigateToImage: (item: CanvasItem) => void;
}

const Sidebar: React.FC<SidebarProps> = ({ messages, isThinking, activeTaskStartedAt = null, activeTaskCount = 0, onSendMessage, contextImage, promptSeed, inputResetKey = 0, imageItems, onClearContextImage, onNavigateToImage }) => {
  const [inputValue, setInputValue] = React.useState('');
  const [selectedAspectRatio, setSelectedAspectRatio] = React.useState<ImageAspectRatio>('auto');
  const [timerNow, setTimerNow] = React.useState(() => Date.now());
  const [imageViewer, setImageViewer] = React.useState<{ item: CanvasItem; title: string } | null>(null);
  const [imageViewerZoom, setImageViewerZoom] = React.useState(1);
  const [imageViewerRotation, setImageViewerRotation] = React.useState(0);
  const [imageViewerPan, setImageViewerPan] = React.useState({ x: 0, y: 0 });
  const scrollRef = useRef<HTMLDivElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const lastContextImageIdRef = useRef<string | null>(null);
  const appliedPromptSeedIdRef = useRef<string | null>(null);
  const imageViewerDragRef = useRef<{
    pointerId: number;
    startX: number;
    startY: number;
    panX: number;
    panY: number;
  } | null>(null);

  const completedImageItems = useMemo(
    () => imageItems.filter(item => item.type === 'image' && item.status === 'completed' && hasUsableImageSource(item)),
    [imageItems]
  );
  const imageItemsById = useMemo(
    () => new Map(imageItems.map(item => [item.id, item])),
    [imageItems]
  );

  useEffect(() => {
    setInputValue('');
    lastContextImageIdRef.current = null;
    appliedPromptSeedIdRef.current = null;
  }, [inputResetKey]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, isThinking]);

  useEffect(() => {
    if (!isThinking || !activeTaskStartedAt) return;

    setTimerNow(Date.now());
    const timerId = window.setInterval(() => {
      setTimerNow(Date.now());
    }, 1000);

    return () => window.clearInterval(timerId);
  }, [activeTaskStartedAt, isThinking]);

  const resetImageViewerTransform = () => {
    setImageViewerZoom(1);
    setImageViewerRotation(0);
    setImageViewerPan({ x: 0, y: 0 });
    imageViewerDragRef.current = null;
  };

  const openImageViewer = (item: CanvasItem, title = getCanvasItemDisplayTitle(item)) => {
    setImageViewer({ item, title });
    resetImageViewerTransform();
  };

  const closeImageViewer = () => {
    setImageViewer(null);
    resetImageViewerTransform();
  };

  const setClampedImageViewerZoom = (nextZoom: number | ((currentZoom: number) => number)) => {
    setImageViewerZoom(currentZoom => {
      const rawNextZoom = typeof nextZoom === 'function' ? nextZoom(currentZoom) : nextZoom;
      return Math.min(IMAGE_VIEWER_MAX_ZOOM, Math.max(IMAGE_VIEWER_MIN_ZOOM, rawNextZoom));
    });
  };

  const zoomImageViewerBy = (delta: number) => {
    setClampedImageViewerZoom(currentZoom => currentZoom + delta);
  };

  const rotateImageViewerBy = (degrees: number) => {
    setImageViewerRotation(currentRotation => currentRotation + degrees);
  };

  const downloadOriginalImage = (item: CanvasItem, title = getCanvasItemDisplayTitle(item)) => {
    const imageSrc = getOriginalImageSrc(item) || getThumbnailImageSrc(item);
    const link = document.createElement('a');
    link.href = imageSrc;
    link.download = `${title || 'livart-image'}.png`;
    link.rel = 'noopener';
    document.body.appendChild(link);
    link.click();
    link.remove();
  };

  const downloadImageViewerImage = () => {
    if (!imageViewer) return;
    downloadOriginalImage(imageViewer.item, imageViewer.title);
  };

  const handleImageViewerWheel = (event: React.WheelEvent<HTMLDivElement>) => {
    if (!imageViewer) return;
    event.preventDefault();
    zoomImageViewerBy(event.deltaY > 0 ? -IMAGE_VIEWER_ZOOM_STEP : IMAGE_VIEWER_ZOOM_STEP);
  };

  const handleImageViewerPointerDown = (event: React.PointerEvent<HTMLImageElement>) => {
    if (event.button !== 0 || imageViewerZoom <= 1) return;
    event.currentTarget.setPointerCapture(event.pointerId);
    imageViewerDragRef.current = {
      pointerId: event.pointerId,
      startX: event.clientX,
      startY: event.clientY,
      panX: imageViewerPan.x,
      panY: imageViewerPan.y
    };
  };

  const handleImageViewerPointerMove = (event: React.PointerEvent<HTMLImageElement>) => {
    const dragState = imageViewerDragRef.current;
    if (!dragState || dragState.pointerId !== event.pointerId) return;
    setImageViewerPan({
      x: dragState.panX + event.clientX - dragState.startX,
      y: dragState.panY + event.clientY - dragState.startY
    });
  };

  const handleImageViewerPointerEnd = (event: React.PointerEvent<HTMLImageElement>) => {
    const dragState = imageViewerDragRef.current;
    if (!dragState || dragState.pointerId !== event.pointerId) return;
    imageViewerDragRef.current = null;
    if (event.currentTarget.hasPointerCapture(event.pointerId)) {
      event.currentTarget.releasePointerCapture(event.pointerId);
    }
  };

  useEffect(() => {
    if (!imageViewer) return;

    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        closeImageViewer();
        return;
      }
      if (event.key === '+' || event.key === '=') {
        zoomImageViewerBy(IMAGE_VIEWER_ZOOM_STEP);
        return;
      }
      if (event.key === '-') {
        zoomImageViewerBy(-IMAGE_VIEWER_ZOOM_STEP);
        return;
      }
      if (event.key === '0') {
        resetImageViewerTransform();
        return;
      }
      if (event.key === '[') {
        rotateImageViewerBy(-90);
        return;
      }
      if (event.key === ']') {
        rotateImageViewerBy(90);
      }
    };

    window.addEventListener('keydown', handleKeyDown);
    return () => window.removeEventListener('keydown', handleKeyDown);
  }, [imageViewer, imageViewerZoom, imageViewerPan]);

  useEffect(() => {
    if (imageViewerZoom > IMAGE_VIEWER_MIN_ZOOM) return;
    setImageViewerPan({ x: 0, y: 0 });
  }, [imageViewerZoom]);

  useEffect(() => {
    const currentContextImageId = contextImage?.id ?? null;
    if (currentContextImageId === lastContextImageIdRef.current) return;

    lastContextImageIdRef.current = currentContextImageId;
    if (!contextImage) return;

    setInputValue(prevValue => {
      const mentionedImages = resolveMentionedImageReferences(prevValue, completedImageItems);
      if (mentionedImages.some(item => item.id === contextImage.id)) return prevValue;
      return insertImageMention(prevValue.trimEnd(), contextImage, completedImageItems);
    });
  }, [completedImageItems, contextImage]);

  useEffect(() => {
    if (!promptSeed) return;
    if (appliedPromptSeedIdRef.current === promptSeed.id) return;

    const targetImage = completedImageItems.find(item => item.id === promptSeed.imageId);
    if (!targetImage) return;

    appliedPromptSeedIdRef.current = promptSeed.id;
    lastContextImageIdRef.current = targetImage.id;
    const mention = insertImageMention('', targetImage, completedImageItems);
    setInputValue(promptSeed.prompt ? `${mention}${promptSeed.prompt}` : mention);
  }, [completedImageItems, promptSeed]);

  const handleInputChange = (nextValue: string) => {
    setInputValue(nextValue);

    if (!contextImage) return;

    const mentionedImages = resolveMentionedImageReferences(nextValue, completedImageItems);
    if (!mentionedImages.some(item => item.id === contextImage.id)) {
      lastContextImageIdRef.current = null;
      onClearContextImage();
    }
  };

  const activeTaskDurationText = activeTaskStartedAt
    ? formatExecutionDuration(timerNow - activeTaskStartedAt)
    : '';

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const prompt = inputValue.trim();
    if (!prompt) return;

    onSendMessage(prompt, selectedAspectRatio);
    setInputValue('');
  };

  const getAspectRatioPreviewStyle = (aspectRatio: ImageAspectRatio): React.CSSProperties => {
    if (aspectRatio === 'auto') {
      return { width: 26, height: 18 };
    }

    const [rawWidth, rawHeight] = aspectRatio.split(':').map(Number);
    const maxWidth = 30;
    const maxHeight = 22;
    const scale = Math.min(maxWidth / rawWidth, maxHeight / rawHeight);
    return {
      width: Math.max(10, Math.round(rawWidth * scale)),
      height: Math.max(10, Math.round(rawHeight * scale))
    };
  };

  const renderFormattedText = (text: string, keyPrefix: string) => {
    return text.split('\n').flatMap((line, lineIndex) => {
      const lineParts = line.split('**').map((part, partIndex) => (
        partIndex % 2 === 1
          ? <strong key={`${keyPrefix}-${lineIndex}-${partIndex}`}>{part}</strong>
          : <React.Fragment key={`${keyPrefix}-${lineIndex}-${partIndex}`}>{part}</React.Fragment>
      ));

      return lineIndex === 0
        ? lineParts
        : [<br key={`${keyPrefix}-${lineIndex}-br`} />, ...lineParts];
    });
  };

  const renderMessageText = (text: string, role: ChatMessage['role']) => {
    const tokens = tokenizeImageReferenceText(text, completedImageItems);
    const isUserMessage = role === 'user';

    return tokens.map((token, index) => {
      if (token.type === 'text') {
        return (
          <React.Fragment key={`text-${index}`}>
            {renderFormattedText(token.text, `text-${index}`)}
          </React.Fragment>
        );
      }

      const previewStyle = getImagePreviewFitStyle(token.item, 42, 20);

      return (
        <button
          key={`mention-${token.item.id}-${index}`}
          type="button"
          title={`定位到 ${getImageReferenceDisplayText(token.item)}`}
          onClick={() => onNavigateToImage(token.item)}
          className={`mx-1 inline-flex max-w-full translate-y-1 items-center gap-1.5 rounded-xl px-2 py-1 align-baseline text-xs font-black shadow-sm transition-all hover:scale-[1.02] active:scale-95 ${
            isUserMessage
              ? 'bg-white text-zinc-700 ring-1 ring-zinc-200 hover:bg-zinc-50 hover:text-zinc-950'
              : 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-100 hover:bg-indigo-100'
          }`}
        >
          <span
            className={`shrink-0 overflow-hidden rounded-md ${
            isUserMessage ? 'bg-zinc-100' : 'bg-white'
          }`}
            style={previewStyle}
          >
            <img src={getThumbnailImageSrc(token.item)} className="h-full w-full object-cover" />
          </span>
          <span className="truncate">{getImageReferenceDisplayText(token.item)}</span>
        </button>
      );
    });
  };

  const normalizeAssistantAnswerText = (text: string) => {
    return text
      .replace(/\r\n/g, '\n')
      .trim()
      .replace(/([：:。！？!?])\s+(?=\d{1,2}[.、)]\s+\S)/g, '$1\n')
      .replace(/\s+(?=\d{1,2}[.、)]\s+\S)/g, '\n')
      .replace(/([：:。！？!?])\s+(?=[一二三四五六七八九十]{1,3}[、.．]\s*\S)/g, '$1\n')
      .replace(/\s+(?=[一二三四五六七八九十]{1,3}[、.．]\s*\S)/g, '\n')
      .replace(/([：:。！？!?])\s+(?=[-•·*]\s+\S)/g, '$1\n')
      .replace(/\s+(?=[-•·*]\s+\S)/g, '\n');
  };

  const splitAssistantParagraphLine = (line: string) => {
    const normalized = line.trim();
    if (normalized.length <= 86 || normalized.includes('http://') || normalized.includes('https://')) {
      return [normalized];
    }

    const sentences = normalized.match(/[^。！？!?；;]+[。！？!?；;]?/g) || [normalized];
    const paragraphs: string[] = [];
    let current = '';

    sentences.forEach(sentence => {
      const nextSentence = sentence.trim();
      if (!nextSentence) return;
      if (current && `${current}${nextSentence}`.length > 74) {
        paragraphs.push(current);
        current = nextSentence;
        return;
      }
      current += nextSentence;
    });

    if (current) {
      paragraphs.push(current);
    }

    return paragraphs.length > 0 ? paragraphs : [normalized];
  };

  const parseAssistantAnswerBlocks = (text: string): AssistantAnswerBlock[] => {
    const lines = normalizeAssistantAnswerText(text)
      .split('\n')
      .map(line => line.trim())
      .filter(Boolean);

    const blocks: AssistantAnswerBlock[] = [];
    const paragraphs: string[] = [];
    const numberedItems: string[] = [];
    const bulletItems: string[] = [];

    const flushParagraphs = () => {
      if (paragraphs.length === 0) return;
      paragraphs.forEach(paragraph => blocks.push({ type: 'paragraph', text: paragraph }));
      paragraphs.length = 0;
    };

    const flushNumberedItems = () => {
      if (numberedItems.length === 0) return;
      blocks.push({ type: 'numbered', items: [...numberedItems] });
      numberedItems.length = 0;
    };

    const flushBulletItems = () => {
      if (bulletItems.length === 0) return;
      blocks.push({ type: 'bullets', items: [...bulletItems] });
      bulletItems.length = 0;
    };

    lines.forEach(line => {
      const numberedMatch = line.match(/^(\d{1,2}|[一二三四五六七八九十]{1,3})[.、)）．]\s*(.+)$/);
      if (numberedMatch) {
        flushParagraphs();
        flushBulletItems();
        numberedItems.push(numberedMatch[2]);
        return;
      }

      const bulletMatch = line.match(/^[-•·*]\s*(.+)$/);
      if (bulletMatch) {
        flushParagraphs();
        flushNumberedItems();
        bulletItems.push(bulletMatch[1]);
        return;
      }

      flushNumberedItems();
      flushBulletItems();
      paragraphs.push(...splitAssistantParagraphLine(line));
    });

    flushParagraphs();
    flushNumberedItems();
    flushBulletItems();

    return blocks;
  };

  const renderAssistantAnswer = (message: ChatMessage) => {
    const blocks = parseAssistantAnswerBlocks(message.text);
    const hasStructuredBlocks = blocks.some(block => block.type !== 'paragraph');
    const isErrorAnswer = /失败|出错|错误|无法|不能|请先|超时|断开/.test(message.text);

    return (
      <div className="grid w-full gap-2.5">
        {blocks.map((block, blockIndex) => {
          if (block.type === 'paragraph') {
            const isLeadParagraph = blockIndex === 0 && hasStructuredBlocks;
            return (
              <p
                key={`assistant-paragraph-${blockIndex}`}
                className={`text-[14px] leading-7 ${
                  isErrorAnswer
                    ? 'font-semibold text-rose-600'
                    : isLeadParagraph
                      ? 'font-semibold text-zinc-900'
                      : 'font-medium text-zinc-700'
                }`}
              >
                {renderMessageText(block.text, message.role)}
              </p>
            );
          }

          if (block.type === 'numbered') {
            return (
              <ol key={`assistant-numbered-${blockIndex}`} className="grid gap-2">
                {block.items.map((item, itemIndex) => (
                  <li
                    key={`assistant-numbered-${blockIndex}-${itemIndex}`}
                    className="flex gap-3 text-[13px] font-medium leading-6 text-zinc-700"
                  >
                    <span className="mt-0.5 flex h-5 min-w-5 items-center justify-center rounded-full bg-zinc-900 text-[10px] font-black leading-none text-white">
                      {itemIndex + 1}
                    </span>
                    <span className="min-w-0 flex-1">
                      {renderMessageText(item, message.role)}
                    </span>
                  </li>
                ))}
              </ol>
            );
          }

          return (
            <ul key={`assistant-bullets-${blockIndex}`} className="grid gap-2">
              {block.items.map((item, itemIndex) => (
                <li
                  key={`assistant-bullets-${blockIndex}-${itemIndex}`}
                  className="flex gap-2.5 text-[13px] font-medium leading-6 text-zinc-700"
                >
                  <span className="mt-[9px] h-1.5 w-1.5 shrink-0 rounded-full bg-indigo-500" />
                  <span className="min-w-0 flex-1">
                    {renderMessageText(item, message.role)}
                  </span>
                </li>
              ))}
            </ul>
          );
        })}
        {renderMessageImages(message)}
      </div>
    );
  };

  const renderMessageImages = (message: ChatMessage) => {
    const imageIds = message.imageIds || [];
    if (imageIds.length === 0) return null;

    const attachedImages = imageIds
      .map(imageId => imageItemsById.get(imageId))
      .filter((item): item is CanvasItem => !!item && item.type === 'image' && item.status === 'completed' && hasUsableImageSource(item));

    if (attachedImages.length === 0) return null;

    return (
      <div className="mt-3 grid gap-2">
        {attachedImages.map((item) => {
          const previewStyle = getImagePreviewFitStyle(item, 230, 180);
          return (
            <div
              key={item.id}
              className="group relative overflow-hidden rounded-2xl bg-white p-2 text-left shadow-sm ring-1 ring-black/5 transition-all hover:-translate-y-0.5 hover:shadow-md hover:ring-indigo-200 active:scale-[0.99]"
            >
              <button
                type="button"
                onClick={() => openImageViewer(item)}
                title="打开大图预览"
                className="block overflow-hidden rounded-xl bg-gray-100 text-left"
                style={previewStyle}
              >
                <img src={getThumbnailImageSrc(item)} className="h-full w-full object-cover transition-transform group-hover:scale-[1.02]" />
              </button>
              <button
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  downloadOriginalImage(item);
                }}
                className="absolute bottom-4 right-4 flex h-8 w-8 items-center justify-center rounded-full bg-white/95 text-zinc-900 opacity-0 shadow-lg ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white group-hover:opacity-100"
                title="下载原始图片"
              >
                <Download size={15} />
              </button>
            </div>
          );
        })}
      </div>
    );
  };

  const getImageResultCards = (message: ChatMessage): ChatImageResultCard[] => {
    if (message.role !== 'assistant') return [];

    const explicitCards = message.imageResultCards || [];
    if (explicitCards.length > 0) return explicitCards;

    return (message.imageIds || []).map(imageId => ({
      imageId
    }));
  };

  const renderImageResultCards = (message: ChatMessage) => {
    const cards = getImageResultCards(message);
    if (cards.length === 0) return null;

    const attachedCards = cards
      .map(card => {
        const item = imageItemsById.get(card.imageId);
        if (!item || item.type !== 'image' || item.status !== 'completed' || !item.content) return null;
        const title = card.title || getCanvasItemDisplayTitle(item);
        return {
          ...card,
          item,
          title,
          modelName: card.modelName || getImageModelDisplayName(),
          description: card.description || buildImageResultDescription(title, message.text.includes('编辑') || message.text.includes('去背景') ? 'edited' : 'generated')
        };
      })
      .filter((card): card is NonNullable<typeof card> => !!card);

    if (attachedCards.length === 0) return null;

    return (
      <div className="grid w-full gap-5">
        {attachedCards.map((card) => {
          const previewStyle = getImagePreviewFitStyle(card.item, 332, 260);
          return (
            <div key={card.item.id} className="w-full text-left">
              <div className="mb-3 flex items-center gap-1.5 text-[12px] font-bold text-zinc-400">
                <Eye size={14} strokeWidth={2.2} />
                <span>{card.modelName}</span>
              </div>
              <h3 className="mb-3 text-[16px] font-black leading-snug text-zinc-800">
                {card.title}
              </h3>
              <div className="group relative overflow-hidden rounded-[4px] bg-zinc-100" style={previewStyle}>
                <button
                  type="button"
                  onClick={() => openImageViewer(card.item, card.title)}
                  title="打开大图预览"
                  className="block h-full w-full text-left"
                >
                  <img
                    src={getThumbnailImageSrc(card.item)}
                    className="h-full w-full object-cover transition-transform group-hover:scale-[1.01]"
                    alt={card.title}
                  />
                </button>
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    downloadOriginalImage(card.item, card.title);
                  }}
                  className="absolute bottom-3 right-3 flex h-9 w-9 items-center justify-center rounded-full bg-white/95 text-zinc-900 opacity-0 shadow-lg ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white group-hover:opacity-100"
                  title="下载原始图片"
                >
                  <Download size={16} />
                </button>
              </div>
              <p className="mt-4 text-[15px] font-medium leading-7 text-zinc-700">
                {card.description}
              </p>
            </div>
          );
        })}
      </div>
    );
  };

  const renderAgentPlan = (message: ChatMessage) => {
    const plan = message.agentPlan;
    if (!plan || message.role !== 'assistant') return null;

    const currentText = getAgentPlanCurrentProgressText(plan, message.text);
    const isRunning = plan.steps.some(step => step.status === 'running');
    return (
      <div className="inline-flex max-w-full items-center gap-2 rounded-[9px] bg-transparent px-0 py-0 text-[13px] font-medium leading-6 text-zinc-700">
        {isRunning ? (
          <Loader2 size={14} className="shrink-0 animate-spin text-indigo-500" />
        ) : (
          <span className="h-2 w-2 shrink-0 rounded-full bg-emerald-500" />
        )}
        <span className="min-w-0 flex-1 truncate">{currentText}</span>
      </div>
    );
  };

  const renderReconnectMessage = (message: ChatMessage) => (
    <div className="inline-flex max-w-full items-center gap-2 rounded-[9px] bg-transparent px-0 py-0 text-[13px] font-medium leading-6 text-amber-700">
      <span className="h-2 w-2 shrink-0 rounded-full bg-amber-500" />
      <span className="min-w-0 flex-1 truncate">{message.text || '等待重连，重连后会自动更新任务结果。'}</span>
    </div>
  );

  const imageViewerControlClass = 'inline-flex h-10 w-10 items-center justify-center rounded-full text-zinc-100 transition-all hover:bg-white/10 hover:text-white active:scale-95 disabled:cursor-not-allowed disabled:opacity-35';

  const renderImageViewer = () => {
    if (!imageViewer) return null;

    const viewerImageSrc = getLargestCanvasImageSrc(imageViewer.item) || getThumbnailImageSrc(imageViewer.item);
    const canZoomOut = imageViewerZoom > IMAGE_VIEWER_MIN_ZOOM;
    const canZoomIn = imageViewerZoom < IMAGE_VIEWER_MAX_ZOOM;

    if (typeof document === 'undefined') return null;

    return createPortal(
      <div
        className="fixed inset-0 z-[6000000] bg-black/50 text-white backdrop-blur-[1px]"
        role="dialog"
        aria-modal="true"
        aria-label="原始大图预览"
        onMouseDown={(event) => {
          if (event.target === event.currentTarget) {
            closeImageViewer();
          }
        }}
      >
        <button
          type="button"
          className="absolute inset-0 z-0 cursor-default"
          onClick={closeImageViewer}
          aria-label="关闭大图预览"
        />
        <button
          type="button"
          className="absolute right-8 top-12 z-20 inline-flex h-11 w-11 items-center justify-center rounded-full bg-zinc-900/80 text-white shadow-2xl ring-1 ring-white/10 transition-all hover:bg-zinc-950 active:scale-95"
          onClick={closeImageViewer}
          title="关闭"
        >
          <X size={22} />
        </button>

        <div className="pointer-events-none absolute inset-0 z-10 flex select-none items-center justify-center overflow-hidden px-10 py-20" onWheel={handleImageViewerWheel}>
          <img
            src={viewerImageSrc}
            alt={imageViewer.title}
            draggable={false}
            onPointerDown={handleImageViewerPointerDown}
            onPointerMove={handleImageViewerPointerMove}
            onPointerUp={handleImageViewerPointerEnd}
            onPointerCancel={handleImageViewerPointerEnd}
            onClick={(event) => event.stopPropagation()}
            className={`pointer-events-auto max-h-[calc(100vh-168px)] max-w-[calc(100vw-80px)] object-contain will-change-transform ${
              imageViewerZoom > 1 ? 'cursor-grab active:cursor-grabbing' : 'cursor-zoom-in'
            }`}
            style={{
              transform: `translate3d(${imageViewerPan.x}px, ${imageViewerPan.y}px, 0) rotate(${imageViewerRotation}deg) scale(${imageViewerZoom})`,
              transformOrigin: 'center center',
              transition: imageViewerDragRef.current ? 'none' : 'transform 120ms ease-out'
            }}
          />
        </div>

        <div className="absolute bottom-9 left-1/2 z-20 flex -translate-x-1/2 items-center gap-1 rounded-full bg-zinc-900/80 px-2 py-2 shadow-2xl ring-1 ring-white/10 backdrop-blur-xl">
            <button
              type="button"
              className={imageViewerControlClass}
              onClick={() => rotateImageViewerBy(-90)}
              title="向左旋转 90°"
            >
              <RotateCcw size={18} />
            </button>
            <button
              type="button"
              className={imageViewerControlClass}
              onClick={() => rotateImageViewerBy(90)}
              title="向右旋转 90°"
            >
              <RotateCw size={18} />
            </button>
            <span className="mx-1 h-5 w-px bg-white/20" />
            <button
              type="button"
              className={imageViewerControlClass}
              onClick={() => zoomImageViewerBy(-IMAGE_VIEWER_ZOOM_STEP)}
              disabled={!canZoomOut}
              title="缩小"
            >
              <ZoomOut size={18} />
            </button>
            <div className="min-w-14 px-1 text-center text-xs font-black tabular-nums text-zinc-200">
              {Math.round(imageViewerZoom * 100)}%
            </div>
            <button
              type="button"
              className={imageViewerControlClass}
              onClick={() => zoomImageViewerBy(IMAGE_VIEWER_ZOOM_STEP)}
              disabled={!canZoomIn}
              title="放大，最多 10 倍"
            >
              <ZoomIn size={18} />
            </button>
            <span className="mx-1 h-5 w-px bg-white/20" />
            <button
              type="button"
              className={imageViewerControlClass}
              onClick={downloadImageViewerImage}
              title="下载原图"
            >
              <Download size={18} />
            </button>
          </div>
      </div>,
      document.body
    );
  };

  return (
    <>
      <div className="w-96 bg-white border-l border-gray-200 h-full flex flex-col z-50 overflow-hidden">
        <div className="p-4 border-b border-gray-50 bg-gray-50/30 flex items-center gap-2">
          <LivartLogo size={32} className="shrink-0" />
          <div>
            <h2 className="font-black text-gray-800 tracking-tight">livart 对话</h2>
            <p className="text-[9px] text-gray-400 uppercase tracking-widest font-black">直接生成与编辑图像</p>
          </div>
        </div>

        <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 space-y-6 scrollbar-hide">
          {messages.map((message) => {
            const resultCards = renderImageResultCards(message);
            const isImageResultMessage = !!resultCards;
            const isAgentPlanMessage = message.role === 'assistant' && !!message.agentPlan && !isImageResultMessage;
            const isReconnectMessage = message.role === 'assistant' && message.agentRunStatus === 'waiting-reconnect' && !isImageResultMessage;

            return (
              <div key={message.id} className={`flex flex-col ${message.role === 'user' ? 'items-end' : 'items-start'}`}>
                <div className={isImageResultMessage || isAgentPlanMessage || isReconnectMessage
                  ? 'w-full bg-white text-sm leading-relaxed text-gray-800'
                  : message.role === 'user'
                    ? 'max-w-[85%] rounded-[9px] bg-[#f5f5f6] px-4 py-3 text-sm font-medium leading-relaxed text-zinc-900'
                    : 'w-full bg-transparent px-0 py-0 text-sm leading-relaxed text-gray-800'
                }>
                  {isImageResultMessage ? resultCards : (
                    isReconnectMessage
                      ? renderReconnectMessage(message)
                      : isAgentPlanMessage
                      ? renderAgentPlan(message)
                      : message.role === 'assistant'
                        ? renderAssistantAnswer(message)
                        : (
                        <>
                          {renderMessageText(message.text, message.role)}
                          {renderMessageImages(message)}
                        </>
                      )
                  )}
                </div>
                <span className="text-[10px] text-gray-400 mt-1 px-1 font-medium">
                  {new Date(message.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
                  {typeof message.durationMs === 'number' && ` · 耗时 ${formatExecutionDuration(message.durationMs)}`}
                </span>
              </div>
            );
          })}

          {isThinking && (
            <div className="flex items-center gap-3 text-black text-sm font-bold animate-pulse">
              <Loader2 className="animate-spin" size={16} />
              <span>
                livart 正在生图{activeTaskCount > 1 ? `（${activeTaskCount} 个任务）` : '中'}...
                {activeTaskDurationText && ` 最早任务已执行 ${activeTaskDurationText}`}
                <span className="ml-1 text-gray-400">可继续提交新任务</span>
              </span>
            </div>
          )}
        </div>

        <div className="p-4 bg-white border-t border-gray-100 space-y-3">
          <form ref={formRef} onSubmit={handleSubmit} className="relative">
            <div className="mb-2 flex items-center gap-2 rounded-2xl border border-gray-100 bg-gray-50 p-1.5">
              <span className="shrink-0 px-2 text-[10px] font-black uppercase tracking-widest text-gray-400">画幅</span>
              <div className="flex min-w-0 flex-1 flex-wrap gap-1.5">
                {IMAGE_ASPECT_RATIO_OPTIONS.map((option) => {
                  const isSelected = selectedAspectRatio === option.value;
                  return (
                    <button
                      key={option.value}
                      type="button"
                      title={option.title}
                      onClick={() => setSelectedAspectRatio(option.value)}
                      className={`flex shrink-0 items-center gap-1 rounded-xl px-1.5 py-1.5 text-[10px] font-black transition-all ${
                        isSelected
                          ? 'bg-black text-white shadow-lg shadow-black/10'
                          : 'bg-white text-gray-500 hover:bg-indigo-50 hover:text-indigo-600'
                      }`}
                    >
                      <span className="flex h-6 w-8 shrink-0 items-center justify-center">
                        <span
                          className={`block rounded-[3px] border-2 ${
                            option.value === 'auto'
                              ? isSelected ? 'border-dashed border-white' : 'border-dashed border-gray-400'
                              : isSelected ? 'border-white bg-white/10' : 'border-gray-500 bg-white'
                          }`}
                          style={getAspectRatioPreviewStyle(option.value)}
                        />
                      </span>
                      <span>{option.label}</span>
                    </button>
                  );
                })}
              </div>
            </div>

            <div className="flex items-end gap-2 w-full pr-2 py-2 pl-3 bg-gray-50 border border-gray-200 rounded-2xl focus-within:ring-4 focus-within:ring-black/5 focus-within:border-black transition-all">
              <ImageMentionEditor
                value={inputValue}
                imageItems={completedImageItems}
                onChange={handleInputChange}
                disabled={false}
                placeholder={contextImage ? '输入对这张图的修改要求...' : '描述画面，或输入 @ 选择参考图...'}
                className="prompt-editor scrollbar-hide min-w-0 flex-1 min-h-[72px] max-h-28 overflow-y-auto overflow-x-hidden bg-transparent py-1.5 text-sm leading-6 font-medium outline-none whitespace-pre-wrap break-words"
                itemHint={() => '点击后插入稳定 ID 引用'}
                onSubmitShortcut={() => formRef.current?.requestSubmit()}
              />
              <button
                type="submit"
                disabled={!inputValue.trim()}
                className="w-10 h-10 text-white rounded-xl hover:scale-105 active:scale-95 transition-all flex items-center justify-center shadow-lg shadow-black/10 flex-shrink-0 bg-black disabled:opacity-30"
              >
                <Send size={18} />
              </button>
            </div>
          </form>
        </div>
      </div>
      {renderImageViewer()}
    </>
  );
};

export default Sidebar;
