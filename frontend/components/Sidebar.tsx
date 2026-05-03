import React, { useEffect, useMemo, useRef } from 'react';
import { createPortal } from 'react-dom';
import type { ActiveImageTaskInfo, ChatMessage, CanvasItem, ChatImageResultCard, ExternalSkillSummary, ImageAspectRatio, ImageResolution, ProductPosterAnalysis, ProductPosterFact, ProductPosterRequest } from '../types';
import { ChevronDown, Copy, Download, Eye, Github, Globe2, Loader2, Package, Send, RotateCcw, RotateCw, Share2, Sparkles, ZoomIn, ZoomOut, X } from 'lucide-react';
import { QRCodeCanvas } from 'qrcode.react';
import { IMAGE_ASPECT_RATIO_OPTIONS, IMAGE_RESOLUTION_OPTIONS } from '../services/imageSizing';
import { getImagePreviewFitStyle, getLargestCanvasImageSrc, getOriginalImageSrc, getThumbnailImageSrc, hasUsableImageSource } from '../services/imageSources';
import { formatExecutionDuration } from '../services/taskTiming';
import { getImageModelDisplayName } from '../services/config';
import { loadExternalSkills } from '../services/externalSkills';
import {
  analyzeProductPosterDescription,
  getAgentPlanCurrentProgressText
} from '../services/agentPlanner';
import {
  getImageReferenceDisplayText,
  insertImageMention,
  resolveMentionedImageReferences,
  tokenizeImageReferenceText
} from '../services/imageReferences';
import { buildImageResultDescription, getCanvasItemDisplayTitle } from '../services/imageTitle';
import { buildLivartImageSharePageUrl, LIVART_SHARE_PROMOTION_TEXT } from '../services/shareLinks';
import ImageMentionEditor from './ImageMentionEditor';
import LivartLogo from './LivartLogo';
import PanoramaViewer from './PanoramaViewer';

const IMAGE_VIEWER_MIN_ZOOM = 1;
const IMAGE_VIEWER_MAX_ZOOM = 10;
const IMAGE_VIEWER_ZOOM_STEP = 1;
const ENABLE_EXTERNAL_SKILLS = true;

const PANORAMA_KEYWORD_PATTERN = /完整球形全景|球形全景|360°球形全景|360度球形全景|equirectangular|spherical panorama/i;

const isLikelyPanoramaImage = (item: CanvasItem) => {
  const ratio = item.width / Math.max(1, item.height);
  const text = [
    item.label,
    item.prompt,
    item.originalPrompt,
    item.optimizedPrompt
  ].filter(Boolean).join(' ');
  return Math.abs(ratio - 2) <= 0.12 && PANORAMA_KEYWORD_PATTERN.test(text);
};

type ImageSharePanelState = {
  itemId: string;
  title: string;
  placement: 'message' | 'viewer';
  wechatScene?: 'friend' | 'timeline';
};

const copyTextToClipboard = async (text: string) => {
  if (navigator.clipboard?.writeText && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.left = '-9999px';
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand('copy');
  textarea.remove();
  if (!copied) throw new Error('复制失败');
};

const toAbsoluteShareUrl = (source: string) => {
  if (!source || source.startsWith('data:')) return '';
  try {
    return new URL(source, window.location.origin).toString();
  } catch {
    return '';
  }
};

const buildWeiboShareUrl = (pageUrl: string, title: string, imageUrl: string) => {
  const params = new URLSearchParams({
    url: pageUrl,
    title,
    pic: imageUrl
  });
  return `https://service.weibo.com/share/share.php?${params.toString()}`;
};

const openExternalShareWindow = (url: string) => {
  window.open(url, '_blank', 'noopener,noreferrer,width=720,height=640');
};

const isMobileShareClient = () => (
  /Android|iPhone|iPad|iPod|Mobile/i.test(navigator.userAgent) || window.innerWidth <= 768
);

const isWeChatBrowser = () => /MicroMessenger/i.test(navigator.userAgent);

const getWeChatShareSceneLabel = (scene: ImageSharePanelState['wechatScene']) => (
  scene === 'timeline' ? '朋友圈' : '微信好友'
);

type AssistantAnswerBlock =
  | { type: 'paragraph'; text: string }
  | { type: 'numbered'; items: string[] }
  | { type: 'bullets'; items: string[] };

type SidebarSendOptions = {
  forcedToolId?: 'tool.product.poster';
  productPoster?: ProductPosterRequest;
  contextImageId?: string;
  contextImageIds?: string[];
  userMessageText?: string;
  enablePromptOptimization?: boolean;
};

type ProductPosterFormState = {
  productImageIds: string[];
  productMode: 'single' | 'series';
  productDescription: string;
  productName: string;
  industry: string;
  material: string;
  size: string;
  color: string;
  style: string;
  detailDesignStyle: string;
  scenarios: string;
  targetAudience: string;
  sellingPoints: string;
  extraDetails: string;
  platformStyle: string;
  posterCount: number;
};

type ProductPosterConversationMessage = {
  id: string;
  role: 'assistant' | 'user';
  text: string;
  analysis?: ProductPosterAnalysis | null;
};

const PRODUCT_POSTER_PLATFORM_OPTIONS = ['淘宝/天猫', '抖音电商', '小红书种草', '朋友圈', '独立站', '通用商业'];
const PRODUCT_POSTER_ANALYSIS_FIELDS: Array<{ key: keyof ProductPosterAnalysis; label: string }> = [
  { key: 'industry', label: '行业' },
  { key: 'productName', label: '产品名称' },
  { key: 'material', label: '材质' },
  { key: 'size', label: '大小/规格' },
  { key: 'color', label: '颜色' },
  { key: 'style', label: '款式/风格' },
  { key: 'detailDesignStyle', label: '详情图设计风格' },
  { key: 'scenarios', label: '适用场景' },
  { key: 'targetAudience', label: '使用人群' },
  { key: 'sellingPoints', label: '核心卖点' },
  { key: 'extraDetails', label: '补充参数' },
  { key: 'platformStyle', label: '平台风格' }
];

const createDefaultProductPosterForm = (productImageIds: string[] = []): ProductPosterFormState => ({
  productImageIds,
  productMode: 'single',
  productDescription: '',
  productName: '',
  industry: '',
  material: '',
  size: '',
  color: '',
  style: '',
  detailDesignStyle: '',
  scenarios: '',
  targetAudience: '',
  sellingPoints: '',
  extraDetails: '',
  platformStyle: PRODUCT_POSTER_PLATFORM_OPTIONS[0],
  posterCount: 3
});

const createProductPosterConversationMessage = (
  role: ProductPosterConversationMessage['role'],
  text: string,
  analysis?: ProductPosterAnalysis | null
): ProductPosterConversationMessage => ({
  id: `${role}-${Date.now()}-${Math.random().toString(36).slice(2, 8)}`,
  role,
  text,
  analysis: analysis || null
});

const createInitialProductPosterConversation = () => ([
  createProductPosterConversationMessage(
    'assistant',
    '把产品信息直接发给我，我会先提取出你已经说明的产品属性并整理成表格；如果你没有说明详情图设计风格，我会通过 WebSearch 补全当前更适合这类产品的流行详情图风格。'
  )
]);

const buildProductPosterAnalysisSnapshotText = (analysis?: ProductPosterAnalysis | null) => {
  if (!analysis) return '';
  const fields = PRODUCT_POSTER_ANALYSIS_FIELDS
    .map(({ key, label }) => {
      const value = analysis[key];
      return typeof value === 'string' && value.trim() ? `${label}=${value.trim()}` : '';
    })
    .filter(Boolean);
  if (analysis.missingInformation?.length) {
    fields.push(`仍缺信息=${analysis.missingInformation.join('、')}`);
  }
  if (analysis.confirmedFacts?.length) {
    fields.push(`图像确认=${analysis.confirmedFacts.map(fact => `${fact.label || fact.key}=${fact.value}`).join('、')}`);
  }
  if (analysis.readyToGenerate) {
    fields.push('状态=信息已足够生成商品详情图');
  }
  return fields.join('；');
};

const normalizePosterFactList = (facts?: ProductPosterFact[] | null) => (facts || [])
  .filter(fact => fact && typeof fact.value === 'string' && fact.value.trim())
  .map(fact => ({
    key: fact.key || '',
    label: fact.label || fact.key || '信息',
    value: fact.value!.trim(),
    source: fact.source || '',
    confidence: fact.confidence || '',
    note: fact.note || ''
  }));

const buildProductPosterConversationContext = (conversation: ProductPosterConversationMessage[]) => {
  const lines = (conversation || [])
    .slice(1)
    .flatMap(message => {
      const text = message.text?.trim();
      const roleLabel = message.role === 'user' ? '用户' : '助手';
      const result = text ? [`${roleLabel}：${text}`] : [];
      const analysisSnapshot = buildProductPosterAnalysisSnapshotText(message.analysis);
      if (analysisSnapshot) {
        result.push(`助手分析快照：${analysisSnapshot}`);
      }
      return result;
    })
    .filter(Boolean);
  const transcript = lines.join('\n').trim();
  if (!transcript) return '';
  return transcript.length > 4000 ? `${transcript.slice(0, 4000)}…` : transcript;
};

interface SidebarProps {
  messages: ChatMessage[];
  isThinking: boolean;
  activeTasks?: ActiveImageTaskInfo[];
  onSendMessage: (text: string, aspectRatio: ImageAspectRatio, imageResolution: ImageResolution, externalSkillId?: string, options?: SidebarSendOptions) => void;
  contextImage: CanvasItem | null;
  promptSeed?: { id: string; imageId: string; prompt?: string } | null;
  inputResetKey?: number;
  productPosterOpenSignal?: number;
  imageItems: CanvasItem[];
  onSelectContextImage: (item: CanvasItem) => void;
  onClearContextImage: () => void;
  onNavigateToImage: (item: CanvasItem) => void;
}

const Sidebar: React.FC<SidebarProps> = ({ messages, isThinking, activeTasks = [], onSendMessage, contextImage, promptSeed, inputResetKey = 0, productPosterOpenSignal = 0, imageItems, onClearContextImage, onNavigateToImage }) => {
  const [inputValue, setInputValue] = React.useState('');
  const [selectedAspectRatio, setSelectedAspectRatio] = React.useState<ImageAspectRatio>('auto');
  const [selectedImageResolution, setSelectedImageResolution] = React.useState<ImageResolution>('2k');
  const [isMobileLayout, setIsMobileLayout] = React.useState(() => (
    typeof window !== 'undefined' ? window.matchMedia('(max-width: 767px)').matches : false
  ));
  const [externalSkills, setExternalSkills] = React.useState<ExternalSkillSummary[]>([]);
  const [selectedExternalSkillId, setSelectedExternalSkillId] = React.useState('');
  const [externalSkillLoadError, setExternalSkillLoadError] = React.useState('');
  const [isPromptOptimizationEnabled, setIsPromptOptimizationEnabled] = React.useState(true);
  const [inputFocusSignal, setInputFocusSignal] = React.useState(0);
  const [isAspectRatioMenuOpen, setIsAspectRatioMenuOpen] = React.useState(false);
  const [isResolutionMenuOpen, setIsResolutionMenuOpen] = React.useState(false);
  const [isSkillMenuOpen, setIsSkillMenuOpen] = React.useState(false);
  const [isActiveTaskListOpen, setIsActiveTaskListOpen] = React.useState(false);
  const [isProductPosterModalOpen, setIsProductPosterModalOpen] = React.useState(false);
  const [productPosterForm, setProductPosterForm] = React.useState<ProductPosterFormState>(() => createDefaultProductPosterForm());
  const [productPosterAnalysis, setProductPosterAnalysis] = React.useState<ProductPosterAnalysis | null>(null);
  const [productPosterConversation, setProductPosterConversation] = React.useState<ProductPosterConversationMessage[]>(() => createInitialProductPosterConversation());
  const [productPosterDraftInput, setProductPosterDraftInput] = React.useState('');
  const [productPosterUserFacts, setProductPosterUserFacts] = React.useState<string[]>([]);
  const [isProductPosterAnalyzing, setIsProductPosterAnalyzing] = React.useState(false);
  const [productPosterError, setProductPosterError] = React.useState('');
  const [timerNow, setTimerNow] = React.useState(() => Date.now());
  const [imageViewer, setImageViewer] = React.useState<{ item: CanvasItem; title: string } | null>(null);
  const [isPanoramaViewerActive, setIsPanoramaViewerActive] = React.useState(false);
  const [imageViewerZoom, setImageViewerZoom] = React.useState(1);
  const [imageViewerRotation, setImageViewerRotation] = React.useState(0);
  const [imageViewerPan, setImageViewerPan] = React.useState({ x: 0, y: 0 });
  const [imageSharePanel, setImageSharePanel] = React.useState<ImageSharePanelState | null>(null);
  const [imageShareCopyNotice, setImageShareCopyNotice] = React.useState('');
  const scrollRef = useRef<HTMLDivElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const aspectRatioMenuRef = useRef<HTMLDivElement>(null);
  const resolutionMenuRef = useRef<HTMLDivElement>(null);
  const skillMenuRef = useRef<HTMLDivElement>(null);
  const productPosterConversationRef = useRef<HTMLDivElement>(null);
  const lastContextImageIdRef = useRef<string | null>(null);
  const lastProductPosterOpenSignalRef = useRef(productPosterOpenSignal);
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
  const selectedAspectRatioOption = useMemo(
    () => IMAGE_ASPECT_RATIO_OPTIONS.find(option => option.value === selectedAspectRatio) || IMAGE_ASPECT_RATIO_OPTIONS[0],
    [selectedAspectRatio]
  );
  const selectedImageResolutionOption = useMemo(
    () => IMAGE_RESOLUTION_OPTIONS.find(option => option.value === selectedImageResolution) || IMAGE_RESOLUTION_OPTIONS[1],
    [selectedImageResolution]
  );
  const selectedExternalSkill = useMemo(
    () => externalSkills.find(skill => skill.id === selectedExternalSkillId) || null,
    [externalSkills, selectedExternalSkillId]
  );
  const sortedActiveTasks = useMemo(
    () => [...activeTasks].sort((firstTask, secondTask) => firstTask.startedAt - secondTask.startedAt),
    [activeTasks]
  );
  const activeTaskCount = sortedActiveTasks.length;

  useEffect(() => {
    if (!isProductPosterModalOpen) return;
    const preferredImageId = contextImage && completedImageItems.some(item => item.id === contextImage.id)
      ? contextImage.id
      : completedImageItems[0]?.id || '';
    const availableImageIds = new Set(completedImageItems.map(item => item.id));
    setProductPosterForm(currentForm => (
      currentForm.productImageIds.some(imageId => availableImageIds.has(imageId))
        ? { ...currentForm, productImageIds: currentForm.productImageIds.filter(imageId => availableImageIds.has(imageId)) }
        : { ...currentForm, productImageIds: preferredImageId ? [preferredImageId] : [] }
    ));
  }, [completedImageItems, contextImage, isProductPosterModalOpen]);

  useEffect(() => {
    if (!isProductPosterModalOpen) return;
    const container = productPosterConversationRef.current;
    if (!container) return;
    const rafId = window.requestAnimationFrame(() => {
      container.scrollTop = container.scrollHeight;
    });
    return () => window.cancelAnimationFrame(rafId);
  }, [isProductPosterModalOpen, productPosterConversation, isProductPosterAnalyzing]);

  useEffect(() => {
    const mediaQuery = window.matchMedia('(max-width: 767px)');
    const handleChange = () => setIsMobileLayout(mediaQuery.matches);
    handleChange();
    mediaQuery.addEventListener('change', handleChange);
    return () => mediaQuery.removeEventListener('change', handleChange);
  }, []);

  useEffect(() => {
    if (!isMobileLayout) return;
    setIsSkillMenuOpen(false);
  }, [isMobileLayout]);

  useEffect(() => {
    setInputValue('');
    lastContextImageIdRef.current = null;
    appliedPromptSeedIdRef.current = null;
  }, [inputResetKey]);

  useEffect(() => {
    if (!ENABLE_EXTERNAL_SKILLS) {
      setExternalSkills([]);
      setSelectedExternalSkillId('');
      setExternalSkillLoadError('');
      setIsSkillMenuOpen(false);
      return;
    }

    let cancelled = false;
    loadExternalSkills()
      .then(skills => {
        if (cancelled) return;
        setExternalSkills(skills);
        setExternalSkillLoadError('');
        setSelectedExternalSkillId(currentSkillId => (
          currentSkillId && skills.some(skill => skill.id === currentSkillId) ? currentSkillId : ''
        ));
      })
      .catch(error => {
        if (cancelled) return;
        setExternalSkills([]);
        setExternalSkillLoadError(error instanceof Error ? error.message : '加载外部 Skill 失败');
      });
    return () => {
      cancelled = true;
    };
  }, []);

  useEffect(() => {
    if (!isAspectRatioMenuOpen && !isResolutionMenuOpen && !isSkillMenuOpen) return;
    const handlePointerDown = (event: PointerEvent) => {
      const targetNode = event.target as Node;
      if (isAspectRatioMenuOpen && !aspectRatioMenuRef.current?.contains(targetNode)) {
        setIsAspectRatioMenuOpen(false);
      }
      if (isResolutionMenuOpen && !resolutionMenuRef.current?.contains(targetNode)) {
        setIsResolutionMenuOpen(false);
      }
      if (isSkillMenuOpen && !skillMenuRef.current?.contains(targetNode)) {
        setIsSkillMenuOpen(false);
      }
    };
    const handleKeyDown = (event: KeyboardEvent) => {
      if (event.key === 'Escape') {
        setIsAspectRatioMenuOpen(false);
        setIsResolutionMenuOpen(false);
        setIsSkillMenuOpen(false);
      }
    };
    document.addEventListener('pointerdown', handlePointerDown);
    document.addEventListener('keydown', handleKeyDown);
    return () => {
      document.removeEventListener('pointerdown', handlePointerDown);
      document.removeEventListener('keydown', handleKeyDown);
    };
  }, [isAspectRatioMenuOpen, isResolutionMenuOpen, isSkillMenuOpen]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, isThinking]);

  useEffect(() => {
    if (!isThinking || activeTaskCount === 0) return;

    setTimerNow(Date.now());
    const timerId = window.setInterval(() => {
      setTimerNow(Date.now());
    }, 1000);

    return () => window.clearInterval(timerId);
  }, [activeTaskCount, isThinking]);

  useEffect(() => {
    if (activeTaskCount > 0) return;
    setIsActiveTaskListOpen(false);
  }, [activeTaskCount]);

  const resetImageViewerTransform = () => {
    setImageViewerZoom(1);
    setImageViewerRotation(0);
    setImageViewerPan({ x: 0, y: 0 });
    imageViewerDragRef.current = null;
  };

  const openImageViewer = (item: CanvasItem, title = getCanvasItemDisplayTitle(item)) => {
    setImageSharePanel(null);
    setImageShareCopyNotice('');
    setImageViewer({ item, title });
    setIsPanoramaViewerActive(isLikelyPanoramaImage(item));
    resetImageViewerTransform();
  };

  const closeImageViewer = () => {
    setImageViewer(null);
    setIsPanoramaViewerActive(false);
    setImageSharePanel(currentPanel => currentPanel?.placement === 'viewer' ? null : currentPanel);
    setImageShareCopyNotice('');
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

  const getImageShareSource = (item: CanvasItem) => (
    toAbsoluteShareUrl(getOriginalImageSrc(item) || getLargestCanvasImageSrc(item) || getThumbnailImageSrc(item))
  );

  const getImageShareTitle = (item: CanvasItem, title = getCanvasItemDisplayTitle(item)) => (
    title || getCanvasItemDisplayTitle(item) || 'livart 图片'
  );

  const getImageSharePageUrl = (item: CanvasItem, title?: string) => {
    const imageUrl = getImageShareSource(item);
    if (!imageUrl) return '';
    return buildLivartImageSharePageUrl({
      imageUrl,
      title: getImageShareTitle(item, title)
    });
  };

  const getImageShareText = (item: CanvasItem, title?: string) => (
    `${getImageShareTitle(item, title)}\n${LIVART_SHARE_PROMOTION_TEXT}`
  );

  const showImageShareNotice = (notice: string) => {
    setImageShareCopyNotice(notice);
    window.setTimeout(() => {
      setImageShareCopyNotice(currentNotice => currentNotice === notice ? '' : currentNotice);
    }, 1800);
  };

  const openImageSharePanel = (
    item: CanvasItem,
    title = getCanvasItemDisplayTitle(item),
    placement: ImageSharePanelState['placement'] = 'message'
  ) => {
    if (!getImageSharePageUrl(item, title)) return;
    setImageShareCopyNotice('');
    setImageSharePanel(currentPanel => (
      currentPanel?.itemId === item.id && currentPanel.placement === placement
        ? null
        : { itemId: item.id, title: getImageShareTitle(item, title), placement }
    ));
  };

  const copyImageShareText = async (item: CanvasItem, title?: string, copiedNotice = '分享文案已复制') => {
    const pageUrl = getImageSharePageUrl(item, title);
    if (!pageUrl) return;

    try {
      await copyTextToClipboard(`${getImageShareText(item, title)}\n${pageUrl}`);
      showImageShareNotice(copiedNotice);
    } catch (error) {
      setImageShareCopyNotice(error instanceof Error ? error.message : '复制失败');
    }
  };

  const nativeShareImage = async (item: CanvasItem, title?: string, fallbackNotice = '已复制，可粘贴到微信') => {
    const pageUrl = getImageSharePageUrl(item, title);
    if (!pageUrl) return false;

    if (navigator.share) {
      try {
        await navigator.share({
          title: getImageShareTitle(item, title),
          text: LIVART_SHARE_PROMOTION_TEXT,
          url: pageUrl
        });
        showImageShareNotice('已打开系统分享面板');
        return true;
      } catch (error) {
        const errorName = error instanceof Error ? error.name : '';
        if (errorName === 'AbortError' || errorName === 'NotAllowedError') return false;
      }
    }

    await copyImageShareText(item, title, fallbackNotice);
    return false;
  };

  const shareImageToWeChat = async (
    item: CanvasItem,
    title: string,
    placement: ImageSharePanelState['placement'],
    scene: NonNullable<ImageSharePanelState['wechatScene']>
  ) => {
    const sharePanelState = { itemId: item.id, title, placement, wechatScene: scene };
    setImageSharePanel(sharePanelState);

    if (isWeChatBrowser()) {
      await copyImageShareText(item, title, `已复制，请点右上角分享到${getWeChatShareSceneLabel(scene)}`);
      return;
    }

    if (isMobileShareClient()) {
      await nativeShareImage(item, title, `已复制，可粘贴到${getWeChatShareSceneLabel(scene)}`);
    }
  };

  const shareImageToWeibo = (item: CanvasItem, title?: string) => {
    const pageUrl = getImageSharePageUrl(item, title);
    const imageUrl = getImageShareSource(item);
    if (!pageUrl || !imageUrl) return;
    openExternalShareWindow(buildWeiboShareUrl(pageUrl, getImageShareText(item, title), imageUrl));
  };

  const renderImageSharePanel = (
    item: CanvasItem,
    title: string,
    placement: ImageSharePanelState['placement']
  ) => {
    if (imageSharePanel?.itemId !== item.id || imageSharePanel.placement !== placement) return null;

    const pageUrl = getImageSharePageUrl(item, title);
    if (!pageUrl) return null;
    const isViewerPlacement = placement === 'viewer';
    const shouldShowWeChatQrCode = !!imageSharePanel.wechatScene && !isMobileShareClient();

    return (
      <div
        className={`z-30 w-72 rounded-2xl border p-3 text-left shadow-2xl backdrop-blur-xl ${
          isViewerPlacement
            ? 'absolute bottom-16 right-0 border-white/10 bg-zinc-950/95 text-white'
            : 'absolute bottom-3 right-14 border-zinc-200 bg-white text-zinc-900'
        }`}
        onClick={event => event.stopPropagation()}
      >
        <div className="mb-2 flex items-center justify-between gap-2">
          <div className="min-w-0">
            <div className={`text-xs font-black ${isViewerPlacement ? 'text-white' : 'text-zinc-900'}`}>分享图片</div>
            <div className={`mt-0.5 truncate text-[10px] font-bold ${isViewerPlacement ? 'text-zinc-400' : 'text-zinc-400'}`}>
              {title}
            </div>
          </div>
          <button
            type="button"
            onClick={() => {
              setImageSharePanel(null);
              setImageShareCopyNotice('');
            }}
            className={`flex h-7 w-7 shrink-0 items-center justify-center rounded-lg transition-colors ${
              isViewerPlacement ? 'text-zinc-400 hover:bg-white/10 hover:text-white' : 'text-zinc-400 hover:bg-zinc-50 hover:text-zinc-900'
            }`}
            title="关闭分享"
          >
            <X size={14} />
          </button>
        </div>
        <div className={`mb-3 rounded-xl px-3 py-2 text-[11px] font-bold leading-5 ${
          isViewerPlacement ? 'bg-white/10 text-zinc-200' : 'bg-zinc-50 text-zinc-600'
        }`}>
          {LIVART_SHARE_PROMOTION_TEXT}
        </div>
        <div className="grid grid-cols-3 gap-1.5">
          <button
            type="button"
            onClick={() => void shareImageToWeChat(item, title, placement, 'friend')}
            className={`flex h-[58px] flex-col items-center justify-center gap-1 rounded-xl p-1.5 text-[10px] font-black transition-colors ${
              isViewerPlacement ? 'text-zinc-200 hover:bg-white/10' : 'text-zinc-500 hover:bg-zinc-50'
            }`}
            title="分享到微信好友"
          >
            <span className="flex h-[30px] w-[30px] items-center justify-center rounded-full bg-[#07c160] text-[10px] font-black text-white">友</span>
            微信好友
          </button>
          <button
            type="button"
            onClick={() => void shareImageToWeChat(item, title, placement, 'timeline')}
            className={`flex h-[58px] flex-col items-center justify-center gap-1 rounded-xl p-1.5 text-[10px] font-black transition-colors ${
              isViewerPlacement ? 'text-zinc-200 hover:bg-white/10' : 'text-zinc-500 hover:bg-zinc-50'
            }`}
            title="分享到朋友圈"
          >
            <span className="flex h-[30px] w-[30px] items-center justify-center rounded-full bg-[#10b981] text-[10px] font-black text-white">圈</span>
            朋友圈
          </button>
          <button
            type="button"
            onClick={() => shareImageToWeibo(item, title)}
            className={`flex h-[58px] flex-col items-center justify-center gap-1 rounded-xl p-1.5 text-[10px] font-black transition-colors ${
              isViewerPlacement ? 'text-zinc-200 hover:bg-white/10' : 'text-zinc-500 hover:bg-zinc-50'
            }`}
            title="分享到微博"
          >
            <span className="flex h-[30px] w-[30px] items-center justify-center rounded-full bg-[#e6162d] text-[10px] font-black text-white">博</span>
            微博
          </button>
        </div>
        {imageSharePanel.wechatScene && shouldShowWeChatQrCode && (
          <div className={`mt-3 rounded-2xl border p-3 ${
            isViewerPlacement ? 'border-emerald-400/20 bg-emerald-400/10' : 'border-emerald-100 bg-emerald-50/70'
          }`}>
            <div className="flex items-start gap-3">
              <div className="rounded-xl bg-white p-2">
                <QRCodeCanvas value={pageUrl} size={112} includeMargin />
              </div>
              <div className="min-w-0 flex-1">
                <div className={`text-xs font-black ${isViewerPlacement ? 'text-emerald-100' : 'text-emerald-900'}`}>
                  微信扫一扫：分享到{getWeChatShareSceneLabel(imageSharePanel.wechatScene)}
                </div>
                <div className={`mt-1 text-[11px] font-bold leading-5 ${isViewerPlacement ? 'text-emerald-200' : 'text-emerald-700'}`}>
                  手机微信扫码打开后，点击右上角“...”分享到{getWeChatShareSceneLabel(imageSharePanel.wechatScene)}。
                </div>
              </div>
            </div>
          </div>
        )}
        {imageSharePanel.wechatScene && !shouldShowWeChatQrCode && (
          <div className={`mt-3 rounded-2xl border p-3 text-[11px] font-bold leading-5 ${
            isViewerPlacement ? 'border-emerald-400/20 bg-emerald-400/10 text-emerald-100' : 'border-emerald-100 bg-emerald-50/70 text-emerald-800'
          }`}>
            {isWeChatBrowser()
              ? `当前已在微信内，已复制分享文案；请点击右上角“…”分享到${getWeChatShareSceneLabel(imageSharePanel.wechatScene)}。`
              : `已尝试打开系统分享面板；如果没有看到微信，请复制文案后手动分享到${getWeChatShareSceneLabel(imageSharePanel.wechatScene)}。`}
          </div>
        )}
        <button
          type="button"
          onClick={() => void copyImageShareText(item, title)}
          className={`mt-3 flex h-9 w-full items-center justify-center gap-2 rounded-xl px-3 text-xs font-black transition-colors active:scale-[0.99] ${
            isViewerPlacement ? 'bg-white text-zinc-950 hover:bg-zinc-100' : 'bg-zinc-900 text-white hover:bg-zinc-800'
          }`}
        >
          <Copy size={14} />
          {imageShareCopyNotice || '复制分享文案'}
        </button>
      </div>
    );
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

  const reuseUserMessage = (messageText: string) => {
    handleInputChange(messageText);
    setInputFocusSignal(currentSignal => currentSignal + 1);
  };

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const prompt = inputValue.trim();
    if (!prompt) return;

    onSendMessage(
      prompt,
      selectedAspectRatio,
      selectedImageResolution,
      ENABLE_EXTERNAL_SKILLS && !isMobileLayout ? selectedExternalSkillId || undefined : undefined,
      {
        enablePromptOptimization: isPromptOptimizationEnabled
      }
    );
    setIsAspectRatioMenuOpen(false);
    setIsResolutionMenuOpen(false);
    setIsSkillMenuOpen(false);
    setInputValue('');
  };

  const openProductPosterModal = () => {
    const preferredImageId = contextImage && completedImageItems.some(item => item.id === contextImage.id)
      ? contextImage.id
      : completedImageItems[0]?.id || '';
    setProductPosterForm(createDefaultProductPosterForm(preferredImageId ? [preferredImageId] : []));
    setProductPosterAnalysis(null);
    setProductPosterConversation(createInitialProductPosterConversation());
    setProductPosterDraftInput('');
    setProductPosterUserFacts([]);
    setProductPosterError('');
    setIsProductPosterModalOpen(true);
    setIsAspectRatioMenuOpen(false);
    setIsResolutionMenuOpen(false);
    setIsSkillMenuOpen(false);
  };

  useEffect(() => {
    if (productPosterOpenSignal === lastProductPosterOpenSignalRef.current) return;
    lastProductPosterOpenSignalRef.current = productPosterOpenSignal;
    if (productPosterOpenSignal > 0) {
      openProductPosterModal();
    }
  }, [productPosterOpenSignal]);

  const updateProductPosterForm = <K extends keyof ProductPosterFormState>(key: K, value: ProductPosterFormState[K]) => {
    setProductPosterForm(currentForm => ({ ...currentForm, [key]: value }));
    setProductPosterError('');
  };

  const toggleProductPosterImage = (imageId: string) => {
    setProductPosterForm(currentForm => {
      const isSelected = currentForm.productImageIds.includes(imageId);
      return {
        ...currentForm,
        productImageIds: isSelected
          ? currentForm.productImageIds.filter(selectedImageId => selectedImageId !== imageId)
          : [...currentForm.productImageIds, imageId].slice(0, 12)
      };
    });
  };

  const buildProductPosterPrompt = (form: ProductPosterFormState) => {
    const modeText = form.productMode === 'series'
      ? '这些产品图是同一个产品系列中的不同产品/SKU，请生成系列商品详情图，不要把它们融合成一个商品。'
      : '这些产品图是同一个产品的不同角度/细节参考，请生成单品商品详情图。';
    const fields = [
      `产品图模式：${form.productMode === 'series' ? '产品系列' : '单个产品'}`,
      form.productDescription && `产品描述：${form.productDescription}`,
      form.productName && `产品名称：${form.productName}`,
      form.industry && `产品行业：${form.industry}`,
      form.material && `材质：${form.material}`,
      form.size && `大小：${form.size}`,
      form.color && `颜色：${form.color}`,
      form.style && `款式：${form.style}`,
      form.detailDesignStyle && `详情图设计风格：${form.detailDesignStyle}`,
      form.scenarios && `适用场景：${form.scenarios}`,
      form.targetAudience && `使用人群：${form.targetAudience}`,
      form.sellingPoints && `核心卖点：${form.sellingPoints}`,
      form.extraDetails && `补充参数：${form.extraDetails}`,
      form.platformStyle && `平台风格：${form.platformStyle}`
    ].filter(Boolean);
    return `基于产品图生成 ${form.posterCount} 张商品详情图，${modeText} 并在画面中加入清晰的中文文字描述，包括短标题、核心卖点，以及在信息已明确时的材质/规格/适用场景等模块；未明确的硬参数不要编造，可改用版型特点、穿着感受、使用场景、风格气质和购买理由来表达。文字要短句化、排版整齐。${fields.join('；') || '请根据产品图自动分析行业特点、使用场景和目标人群。'}`;
  };

  const applyProductPosterAnalysis = (analysis: ProductPosterAnalysis) => {
    setProductPosterForm(currentForm => ({
      ...currentForm,
      productName: analysis.productName?.trim() || currentForm.productName,
      industry: analysis.industry?.trim() || currentForm.industry,
      material: analysis.material?.trim() || currentForm.material,
      size: analysis.size?.trim() || currentForm.size,
      color: analysis.color?.trim() || currentForm.color,
      style: analysis.style?.trim() || currentForm.style,
      detailDesignStyle: analysis.detailDesignStyle?.trim() || currentForm.detailDesignStyle,
      scenarios: analysis.scenarios?.trim() || currentForm.scenarios,
      targetAudience: analysis.targetAudience?.trim() || currentForm.targetAudience,
      sellingPoints: analysis.sellingPoints?.trim() || currentForm.sellingPoints,
      extraDetails: analysis.extraDetails?.trim() || currentForm.extraDetails,
      platformStyle: PRODUCT_POSTER_PLATFORM_OPTIONS.includes(analysis.platformStyle || '')
        ? analysis.platformStyle || currentForm.platformStyle
        : currentForm.platformStyle
    }));
  };

  const buildProductPosterAssistantReply = (analysis: ProductPosterAnalysis) => {
    const assistantMessage = analysis.assistantMessage?.trim();
    if (assistantMessage) return assistantMessage;
    if (analysis.readyToGenerate) {
      return '我已经把商品关键信息整理好了，现在可以开始生成商品详情图。';
    }
    return analysis.nextQuestion?.trim() || '我还需要更多产品信息，继续补充一下吧。';
  };

  const sendProductPosterMessage = async (event?: React.FormEvent) => {
    event?.preventDefault();
    const message = productPosterDraftInput.trim();
    if (!message) {
      setProductPosterError('请先输入产品特点');
      return;
    }

    const nextFacts = [...productPosterUserFacts, message];
    const combinedDescription = nextFacts.join('\n');
    const userMessage = createProductPosterConversationMessage('user', message);
    const nextConversation = [...productPosterConversation, userMessage];
    setProductPosterConversation(nextConversation);
    setProductPosterUserFacts(nextFacts);
    setProductPosterDraftInput('');
    setIsProductPosterAnalyzing(true);
    setProductPosterError('');
    setProductPosterForm(currentForm => ({
      ...currentForm,
      productDescription: combinedDescription
    }));
    const selectedProductImages = productPosterForm.productImageIds
      .map(imageId => completedImageItems.find(item => item.id === imageId))
      .filter((item): item is CanvasItem => Boolean(item));
    try {
      const analysis = await analyzeProductPosterDescription(combinedDescription, selectedProductImages, {
        latestUserMessage: message,
        conversationContext: buildProductPosterConversationContext(nextConversation)
      });
      setProductPosterAnalysis(analysis);
      applyProductPosterAnalysis(analysis);
      setProductPosterConversation(currentConversation => [
        ...currentConversation,
        createProductPosterConversationMessage('assistant', buildProductPosterAssistantReply(analysis), analysis)
      ]);
    } catch (error) {
      setProductPosterAnalysis(null);
      const errorMessage = error instanceof Error ? error.message : '产品特征分析失败';
      setProductPosterError(errorMessage);
      setProductPosterConversation(currentConversation => [
        ...currentConversation,
        createProductPosterConversationMessage('assistant', errorMessage)
      ]);
    } finally {
      setIsProductPosterAnalyzing(false);
    }
  };

  const submitProductPoster = () => {
    const selectedProductImages = productPosterForm.productImageIds
      .map(imageId => completedImageItems.find(item => item.id === imageId))
      .filter((item): item is CanvasItem => Boolean(item));
    const productImage = selectedProductImages[0] || null;
    if (!productImage) {
      setProductPosterError('请先选择至少一张产品图片');
      return;
    }
    if (!productPosterAnalysis) {
      setProductPosterError('请先在右侧对话框里描述产品，让我先分析商品信息');
      return;
    }
    if (!productPosterAnalysis.readyToGenerate) {
      setProductPosterError(productPosterAnalysis.nextQuestion || '当前信息还不够，请继续补充后再生成');
      return;
    }

    const prompt = buildProductPosterPrompt(productPosterForm);
    const productPoster: ProductPosterRequest = {
      productImageId: productImage.id,
      productImageIds: selectedProductImages.map(item => item.id),
      productMode: productPosterForm.productMode,
      productDescription: productPosterForm.productDescription.trim(),
      productName: productPosterForm.productName.trim(),
      industry: productPosterForm.industry.trim(),
      material: productPosterForm.material.trim(),
      size: productPosterForm.size.trim(),
      color: productPosterForm.color.trim(),
      style: productPosterForm.style.trim(),
      detailDesignStyle: productPosterForm.detailDesignStyle.trim(),
      scenarios: productPosterForm.scenarios.trim(),
      targetAudience: productPosterForm.targetAudience.trim(),
      sellingPoints: productPosterForm.sellingPoints.trim(),
      extraDetails: productPosterForm.extraDetails.trim(),
      platformStyle: productPosterForm.platformStyle.trim(),
      conversationContext: buildProductPosterConversationContext(productPosterConversation),
      posterCount: productPosterForm.posterCount
    };

    onSendMessage(prompt, selectedAspectRatio, selectedImageResolution, undefined, {
      forcedToolId: 'tool.product.poster',
      productPoster,
      contextImageId: productImage.id,
      contextImageIds: selectedProductImages.map(item => item.id),
      userMessageText: `使用 ${selectedProductImages.length} 张${productPosterForm.productMode === 'series' ? '系列产品图' : '产品图'}生成 ${productPosterForm.posterCount} 张商品详情图：${productPosterForm.productName.trim() || getCanvasItemDisplayTitle(productImage)}`,
      enablePromptOptimization: isPromptOptimizationEnabled
    });
    setIsProductPosterModalOpen(false);
    setProductPosterError('');
  };

  const renderProductPosterAnalysisSnapshot = (analysis: ProductPosterAnalysis) => {
    const fields = PRODUCT_POSTER_ANALYSIS_FIELDS
      .map(field => {
        const value = String(analysis[field.key] || '').trim();
        return value ? { label: field.label, value } : null;
      })
      .filter((field): field is { label: string; value: string } => Boolean(field));
    const confirmedFacts = normalizePosterFactList(analysis.confirmedFacts);
    const suggestedFacts = normalizePosterFactList(analysis.suggestedFacts);

    return (
      <div className="mt-2 grid gap-2 rounded-2xl border border-zinc-200 bg-white p-3">
        {analysis.summary && (
          <div className="rounded-xl bg-zinc-50 px-3 py-2">
            <div className="text-[10px] font-black uppercase tracking-[0.18em] text-zinc-400">当前理解</div>
            <div className="mt-1 text-xs font-bold leading-5 text-zinc-800">{analysis.summary}</div>
          </div>
        )}

        {fields.length > 0 && (
          <div className="grid gap-2 md:grid-cols-2">
            {fields.map(field => (
              <div key={field.label} className="rounded-xl border border-zinc-100 bg-zinc-50 px-3 py-2">
                <div className="text-[10px] font-black text-zinc-400">{field.label}</div>
                <div className="mt-1 text-xs font-bold leading-5 text-zinc-800">{field.value}</div>
              </div>
            ))}
          </div>
        )}

        {confirmedFacts.length > 0 && (
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2">
            <div className="text-[10px] font-black uppercase tracking-[0.16em] text-emerald-600">图像已确认</div>
            <div className="mt-2 grid gap-2">
              {confirmedFacts.map((fact, index) => (
                <div key={`${fact.key}-${index}`} className="rounded-xl bg-white/80 px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-[11px] font-black text-emerald-700">{fact.label}</span>
                    {fact.source && <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-black text-emerald-700">{fact.source}</span>}
                    {fact.confidence && <span className="rounded-full bg-emerald-100 px-2 py-0.5 text-[10px] font-black text-emerald-700">{fact.confidence}</span>}
                  </div>
                  <div className="mt-1 text-xs font-bold leading-5 text-zinc-800">{fact.value}</div>
                  {fact.note && <div className="mt-1 text-[11px] font-medium leading-4 text-zinc-500">{fact.note}</div>}
                </div>
              ))}
            </div>
          </div>
        )}

        {suggestedFacts.length > 0 && (
          <div className="rounded-xl border border-amber-200 bg-amber-50 px-3 py-2">
            <div className="text-[10px] font-black uppercase tracking-[0.16em] text-amber-700">候选建议（待确认）</div>
            <div className="mt-2 grid gap-2">
              {suggestedFacts.map((fact, index) => (
                <div key={`${fact.key}-${index}`} className="rounded-xl bg-white/85 px-3 py-2">
                  <div className="flex flex-wrap items-center gap-2">
                    <span className="text-[11px] font-black text-amber-700">{fact.label}</span>
                    {fact.confidence && <span className="rounded-full bg-amber-100 px-2 py-0.5 text-[10px] font-black text-amber-700">{fact.confidence}</span>}
                  </div>
                  <div className="mt-1 text-xs font-bold leading-5 text-zinc-800">{fact.value}</div>
                  {fact.note && <div className="mt-1 text-[11px] font-medium leading-4 text-zinc-500">{fact.note}</div>}
                </div>
              ))}
            </div>
          </div>
        )}

        {analysis.missingInformation && analysis.missingInformation.length > 0 && (
          <div className="rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2">
            <div className="text-[10px] font-black uppercase tracking-[0.16em] text-zinc-500">仍需补充</div>
            <div className="mt-1 text-xs font-bold leading-5 text-zinc-700">{analysis.missingInformation.join('、')}</div>
          </div>
        )}

        {analysis.readyToGenerate && (
          <div className="rounded-xl border border-emerald-200 bg-emerald-50 px-3 py-2 text-xs font-black text-emerald-700">
            信息已足够，可以开始生成商品详情图。
          </div>
        )}
      </div>
    );
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
          const title = getCanvasItemDisplayTitle(item);
          const isPanorama = isLikelyPanoramaImage(item);
          const panoramaSrc = getLargestCanvasImageSrc(item) || getThumbnailImageSrc(item);
          const panoramaStyle = isPanorama ? { ...previewStyle, height: '150px' } : previewStyle;
          return (
            <div
              key={item.id}
              className="group relative overflow-visible rounded-2xl bg-white p-2 text-left shadow-sm ring-1 ring-black/5 transition-all hover:-translate-y-0.5 hover:shadow-md hover:ring-indigo-200 active:scale-[0.99]"
            >
              {isPanorama && panoramaSrc ? (
                <div className="relative overflow-hidden rounded-xl bg-black text-left" style={panoramaStyle}>
                  <PanoramaViewer src={panoramaSrc} title={title} compact />
                  <span className="pointer-events-none absolute left-2 top-2 z-20 rounded-full bg-black/55 px-2 py-1 text-[11px] font-black text-white backdrop-blur">
                    360° 全景
                  </span>
                  <button
                    type="button"
                    onClick={() => openImageViewer(item, title)}
                    className="absolute right-2 top-2 z-20 rounded-full bg-white/95 px-2.5 py-1 text-[11px] font-black text-zinc-900 shadow-sm ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white"
                    title="打开大图预览"
                  >
                    查看大图
                  </button>
                </div>
              ) : (
                <button
                  type="button"
                  onClick={() => openImageViewer(item, title)}
                  title="打开大图预览"
                  className="block overflow-hidden rounded-xl bg-gray-100 text-left"
                  style={previewStyle}
                >
                  <img src={getThumbnailImageSrc(item)} className="h-full w-full object-cover transition-transform group-hover:scale-[1.02]" />
                </button>
              )}
              <button
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  openImageSharePanel(item, title, 'message');
                }}
                className="absolute bottom-14 right-4 z-30 flex h-8 w-8 items-center justify-center rounded-full bg-white/95 text-zinc-900 opacity-0 shadow-lg ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white group-hover:opacity-100"
                title="分享图片"
              >
                <Share2 size={15} />
              </button>
              <button
                type="button"
                onClick={(event) => {
                  event.stopPropagation();
                  downloadOriginalImage(item, title);
                }}
                className="absolute bottom-4 right-4 z-30 flex h-8 w-8 items-center justify-center rounded-full bg-white/95 text-zinc-900 opacity-0 shadow-lg ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white group-hover:opacity-100"
                title="下载原始图片"
              >
                <Download size={15} />
              </button>
              {renderImageSharePanel(item, title, 'message')}
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
          const isPanorama = isLikelyPanoramaImage(card.item);
          const panoramaSrc = getLargestCanvasImageSrc(card.item) || getThumbnailImageSrc(card.item);
          return (
            <div key={card.item.id} className="w-full text-left">
              <div className="mb-3 flex items-center gap-1.5 text-[12px] font-bold text-zinc-400">
                <Eye size={14} strokeWidth={2.2} />
                <span>{card.modelName}</span>
              </div>
              <h3 className="mb-3 text-[16px] font-black leading-snug text-zinc-800">
                {card.title}
              </h3>
              <div className="group relative overflow-visible rounded-[4px] bg-zinc-100" style={previewStyle}>
                {isPanorama && panoramaSrc ? (
                  <div className="h-full w-full overflow-hidden rounded-[4px] bg-black">
                    <PanoramaViewer src={panoramaSrc} title={card.title} compact />
                    <span className="pointer-events-none absolute left-3 top-3 z-20 rounded-full bg-black/55 px-2.5 py-1 text-[11px] font-black text-white backdrop-blur">
                      360° 全景
                    </span>
                    <button
                      type="button"
                      onClick={() => openImageViewer(card.item, card.title)}
                      className="absolute right-3 top-3 z-20 rounded-full bg-white/95 px-3 py-1.5 text-[12px] font-black text-zinc-900 shadow-sm ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white"
                      title="打开大图预览"
                    >
                      查看大图
                    </button>
                  </div>
                ) : (
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
                )}
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    openImageSharePanel(card.item, card.title, 'message');
                  }}
                  className="absolute bottom-14 right-3 z-30 flex h-9 w-9 items-center justify-center rounded-full bg-white/95 text-zinc-900 opacity-0 shadow-lg ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white group-hover:opacity-100"
                  title="分享图片"
                >
                  <Share2 size={16} />
                </button>
                <button
                  type="button"
                  onClick={(event) => {
                    event.stopPropagation();
                    downloadOriginalImage(card.item, card.title);
                  }}
                  className="absolute bottom-3 right-3 z-30 flex h-9 w-9 items-center justify-center rounded-full bg-white/95 text-zinc-900 opacity-0 shadow-lg ring-1 ring-black/10 transition-all hover:scale-105 hover:bg-white group-hover:opacity-100"
                  title="下载原始图片"
                >
                  <Download size={16} />
                </button>
                {renderImageSharePanel(card.item, card.title, 'message')}
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

  const renderProductPosterModal = () => {
    if (!isProductPosterModalOpen || typeof document === 'undefined') return null;
    const selectedProductImages = productPosterForm.productImageIds
      .map(imageId => completedImageItems.find(item => item.id === imageId))
      .filter((item): item is CanvasItem => Boolean(item));
    const inputClass = 'w-full rounded-xl border border-zinc-200 bg-zinc-50 px-3 py-2 text-sm font-medium text-zinc-900 outline-none transition-all placeholder:text-zinc-400 focus:border-zinc-900 focus:bg-white focus:ring-4 focus:ring-zinc-900/5';
    const labelClass = 'mb-1.5 block text-[11px] font-black text-zinc-500';
    const productPosterReady = Boolean(productPosterAnalysis?.readyToGenerate);

    return createPortal(
      <div className="fixed inset-0 z-[6000001] flex items-center justify-center bg-zinc-950/35 px-4 py-6 backdrop-blur-sm">
        <div
          className="flex max-h-[92vh] w-full max-w-3xl flex-col overflow-hidden rounded-3xl border border-zinc-200 bg-white shadow-2xl shadow-zinc-950/20"
        >
          <div className="flex items-center justify-between gap-3 border-b border-zinc-100 px-5 py-4">
            <div className="flex items-center gap-3">
              <div className="flex h-10 w-10 items-center justify-center rounded-2xl bg-zinc-900 text-white">
                <Package size={20} />
              </div>
              <div>
                <h3 className="text-base font-black text-zinc-950">商品详情图 Agent</h3>
                <p className="text-xs font-medium text-zinc-500">上传产品图后，自动分析行业并生成多张商品详情图</p>
              </div>
            </div>
            <button
              type="button"
              onClick={() => setIsProductPosterModalOpen(false)}
              className="flex h-9 w-9 items-center justify-center rounded-xl text-zinc-400 transition-colors hover:bg-zinc-50 hover:text-zinc-950"
              title="关闭"
            >
              <X size={18} />
            </button>
          </div>

          <div className="grid min-h-0 flex-1 gap-4 overflow-hidden p-5 md:grid-cols-[220px,1fr]">
            <div className="space-y-3">
              <label className={labelClass}>选择产品图</label>
              <div className="grid grid-cols-2 gap-2">
                {[
                  { value: 'single' as const, title: '单品模式', description: '同一商品的角度/细节' },
                  { value: 'series' as const, title: '系列模式', description: '同系列不同产品/SKU' }
                ].map(option => {
                  const isActive = productPosterForm.productMode === option.value;
                  return (
                    <button
                      key={option.value}
                      type="button"
                      onClick={() => updateProductPosterForm('productMode', option.value)}
                      className={`rounded-2xl border px-3 py-2 text-left transition-all ${
                        isActive ? 'border-zinc-950 bg-zinc-950 text-white' : 'border-zinc-100 bg-zinc-50 text-zinc-700 hover:border-zinc-300 hover:bg-white'
                      }`}
                    >
                      <span className="block text-xs font-black">{option.title}</span>
                      <span className={`mt-0.5 block text-[10px] font-bold ${isActive ? 'text-white/65' : 'text-zinc-400'}`}>{option.description}</span>
                    </button>
                  );
                })}
              </div>
              <div className="rounded-2xl bg-zinc-50 px-3 py-2 text-[11px] font-bold leading-5 text-zinc-500">
                {productPosterForm.productMode === 'series'
                  ? '可选择同一系列的多张不同产品图。系统会保留每个产品差异，生成系列展示、对比和组合详情图。'
                  : '可选择多张同一产品图片。第一张作为主图，其余作为侧面、细节、包装或材质参考。'}
              </div>
              <div className="max-h-[420px] space-y-2 overflow-y-auto pr-1">
                {completedImageItems.map(item => {
                  const selectedIndex = productPosterForm.productImageIds.indexOf(item.id);
                  const isSelected = selectedIndex >= 0;
                  return (
                    <button
                      key={item.id}
                      type="button"
                      onClick={() => toggleProductPosterImage(item.id)}
                      className={`flex w-full items-center gap-3 rounded-2xl border p-2 text-left transition-all ${
                        isSelected ? 'border-zinc-950 bg-zinc-950 text-white' : 'border-zinc-100 bg-zinc-50 text-zinc-800 hover:border-zinc-300 hover:bg-white'
                      }`}
                    >
                      <span className="flex h-14 w-14 shrink-0 items-center justify-center overflow-hidden rounded-xl bg-white/80">
                        <img src={getThumbnailImageSrc(item)} className="h-full w-full object-cover" />
                      </span>
                      <span className="min-w-0 flex-1">
                        <span className="flex items-center gap-1.5">
                          <span className="block min-w-0 flex-1 truncate text-xs font-black">{getCanvasItemDisplayTitle(item)}</span>
                          {isSelected && (
                            <span className={`shrink-0 rounded-full px-2 py-0.5 text-[10px] font-black ${selectedIndex === 0 ? 'bg-amber-400 text-zinc-950' : 'bg-white/15 text-white'}`}>
                              {productPosterForm.productMode === 'series'
                                ? `产品 ${selectedIndex + 1}`
                                : selectedIndex === 0 ? '主图' : `参考 ${selectedIndex}`}
                            </span>
                          )}
                        </span>
                      </span>
                    </button>
                  );
                })}
                {completedImageItems.length === 0 && (
                  <div className="rounded-2xl border border-dashed border-zinc-200 p-4 text-center text-xs font-bold text-zinc-400">
                    先拖入或上传一张产品图
                  </div>
                )}
              </div>
            </div>

            <div className="flex min-h-0 flex-col gap-3">
              <div
                ref={productPosterConversationRef}
                className="min-h-[420px] flex-1 overflow-y-auto rounded-3xl border border-zinc-200 bg-zinc-50/90 p-3"
              >
                <div className="grid gap-3">
                  {productPosterConversation.map(message => (
                    <div key={message.id} className={`flex ${message.role === 'user' ? 'justify-end' : 'justify-start'}`}>
                      <div className={`max-w-[88%] ${message.role === 'user' ? 'items-end' : 'items-start'} grid gap-1.5`}>
                        <div
                          className={`rounded-2xl px-4 py-3 text-sm font-bold leading-6 ${
                            message.role === 'user'
                              ? 'bg-zinc-950 text-white'
                              : 'border border-zinc-200 bg-white text-zinc-800'
                          }`}
                        >
                          <div className="whitespace-pre-wrap break-words">{message.text}</div>
                        </div>
                        {message.role === 'assistant' && message.analysis && renderProductPosterAnalysisSnapshot(message.analysis)}
                      </div>
                    </div>
                  ))}

                  {isProductPosterAnalyzing && (
                    <div className="flex justify-start">
                      <div className="inline-flex items-center gap-2 rounded-2xl border border-zinc-200 bg-white px-4 py-3 text-sm font-bold text-zinc-600">
                        <Loader2 size={16} className="animate-spin" />
                        正在分析产品信息...
                      </div>
                    </div>
                  )}
                </div>
              </div>

              <div className="grid gap-3 rounded-3xl border border-zinc-200 bg-white p-3">
                <form onSubmit={sendProductPosterMessage} className="grid gap-3">
                  <div>
                    <label htmlFor="product-poster-input" className={labelClass}>补充产品信息</label>
                    <div className="relative mb-5">
                      <textarea
                        id="product-poster-input"
                        className={`${inputClass} min-h-[112px] resize-none leading-6`}
                        value={productPosterDraftInput}
                        onChange={event => {
                          setProductPosterDraftInput(event.target.value);
                          if (productPosterError) setProductPosterError('');
                        }}
                        placeholder="像聊天一样告诉我产品信息，例如：这是一款香水，面向 25-35 岁女性，偏高级送礼场景..."
                      />
                      <button
                        type="submit"
                        disabled={isProductPosterAnalyzing || !productPosterDraftInput.trim()}
                        className="absolute -bottom-4 right-3 inline-flex h-9 items-center gap-1.5 rounded-lg bg-zinc-950 px-3 text-xs font-black text-white shadow-lg shadow-zinc-950/20 transition-all hover:scale-[1.02] active:scale-95 disabled:cursor-not-allowed disabled:opacity-40"
                      >
                        {isProductPosterAnalyzing ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}
                        发送
                      </button>
                    </div>
                  </div>

                  <div className="grid gap-3 md:grid-cols-2">
                    <label>
                      <span className={labelClass}>平台风格</span>
                      <select className={inputClass} value={productPosterForm.platformStyle} onChange={event => updateProductPosterForm('platformStyle', event.target.value)}>
                        {PRODUCT_POSTER_PLATFORM_OPTIONS.map(option => (
                          <option key={option} value={option}>{option}</option>
                        ))}
                      </select>
                    </label>
                    <label>
                      <span className={labelClass}>生成数量</span>
                      <select className={inputClass} value={productPosterForm.posterCount} onChange={event => updateProductPosterForm('posterCount', Number(event.target.value))}>
                        {[1, 2, 3, 4, 5, 6].map(count => (
                          <option key={count} value={count}>{count} 张</option>
                        ))}
                      </select>
                    </label>
                  </div>

                  {productPosterError && (
                    <div className="rounded-2xl bg-red-50 px-3 py-2 text-xs font-bold text-red-600">{productPosterError}</div>
                  )}
                </form>
              </div>
            </div>
          </div>

          <div className="flex items-center justify-between gap-3 border-t border-zinc-100 px-5 py-4">
            <div className="text-xs font-bold text-zinc-400">先通过对话补齐商品信息，准备完成后再并行生成商品详情图。</div>
            <div className="flex items-center gap-2">
              <button type="button" onClick={() => setIsProductPosterModalOpen(false)} className="h-10 rounded-xl px-4 text-sm font-black text-zinc-500 transition-colors hover:bg-zinc-50 hover:text-zinc-950">取消</button>
              <button type="button" onClick={submitProductPoster} disabled={selectedProductImages.length === 0 || !productPosterAnalysis || !productPosterReady || isProductPosterAnalyzing} className="inline-flex h-10 items-center gap-2 rounded-xl bg-zinc-950 px-4 text-sm font-black text-white transition-all hover:scale-[1.02] active:scale-95 disabled:cursor-not-allowed disabled:opacity-40">
                <Sparkles size={16} />
                确认并生成详情图
              </button>
            </div>
          </div>
        </div>
      </div>,
      document.body
    );
  };

  const imageViewerControlClass = 'inline-flex h-10 w-10 items-center justify-center rounded-full text-zinc-100 transition-all hover:bg-white/10 hover:text-white active:scale-95 disabled:cursor-not-allowed disabled:opacity-35';

  const renderImageViewer = () => {
    if (!imageViewer) return null;

    const viewerImageSrc = getLargestCanvasImageSrc(imageViewer.item) || getThumbnailImageSrc(imageViewer.item);
    const canZoomOut = imageViewerZoom > IMAGE_VIEWER_MIN_ZOOM;
    const canZoomIn = imageViewerZoom < IMAGE_VIEWER_MAX_ZOOM;
    const canUsePanoramaViewer = isLikelyPanoramaImage(imageViewer.item) && !!viewerImageSrc;

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

        {isPanoramaViewerActive && canUsePanoramaViewer ? (
          <div className="absolute inset-x-8 inset-y-20 z-10 overflow-hidden rounded-[4px] bg-black ring-1 ring-white/10">
            <PanoramaViewer src={viewerImageSrc} title={imageViewer.title} />
          </div>
        ) : (
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
        )}

        <div className="absolute bottom-9 left-1/2 z-20 flex -translate-x-1/2 items-center gap-1 rounded-full bg-zinc-900/80 px-2 py-2 shadow-2xl ring-1 ring-white/10 backdrop-blur-xl">
            {canUsePanoramaViewer && (
              <>
                <button
                  type="button"
                  className={`${imageViewerControlClass} ${isPanoramaViewerActive ? 'bg-white text-zinc-950 hover:bg-white' : ''}`}
                  onClick={() => {
                    setIsPanoramaViewerActive(currentValue => !currentValue);
                    resetImageViewerTransform();
                  }}
                  title={isPanoramaViewerActive ? '切换为平面查看' : '切换为 360° 全景查看'}
                >
                  <Globe2 size={18} />
                </button>
                <span className="mx-1 h-5 w-px bg-white/20" />
              </>
            )}
            {!isPanoramaViewerActive && (
              <>
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
              </>
            )}
            <span className="mx-1 h-5 w-px bg-white/20" />
            <button
              type="button"
              className={imageViewerControlClass}
              onClick={downloadImageViewerImage}
              title="下载原图"
            >
              <Download size={18} />
            </button>
            <button
              type="button"
              className={imageViewerControlClass}
              onClick={(event) => {
                event.stopPropagation();
                openImageSharePanel(imageViewer.item, imageViewer.title, 'viewer');
              }}
              title="分享图片"
            >
              <Share2 size={18} />
            </button>
            {renderImageSharePanel(imageViewer.item, imageViewer.title, 'viewer')}
          </div>
      </div>,
      document.body
    );
  };

  return (
    <>
      <div className="h-full w-full bg-white flex flex-col z-50 overflow-hidden md:w-96 md:border-l md:border-gray-200">
        <div className="hidden p-4 border-b border-gray-50 bg-gray-50/30 items-center gap-2 md:flex">
          <LivartLogo size={32} className="shrink-0" />
          <div>
            <h2 className="font-black text-gray-800 tracking-tight">livart 对话</h2>
            <p className="text-[9px] text-gray-400 uppercase tracking-widest font-black">直接生成与编辑图像</p>
          </div>
        </div>

        <div ref={scrollRef} className="flex-1 overflow-y-auto p-3 space-y-4 scrollbar-hide md:p-4 md:space-y-6">
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
                          {message.text.trim() && (
                            <div className="mt-2 flex justify-end">
                              <button
                                type="button"
                                onClick={() => reuseUserMessage(message.text)}
                                className="inline-flex items-center gap-1.5 rounded-full border border-zinc-200 bg-white/90 px-2.5 py-1 text-[11px] font-black text-zinc-600 transition-all hover:border-zinc-300 hover:bg-white hover:text-zinc-950 active:scale-95"
                                title="把这条消息重新写入输入框"
                              >
                                <RotateCcw size={12} />
                                复用
                              </button>
                            </div>
                          )}
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
            <div className="relative overflow-visible text-sm font-bold text-black">
              <div className="flex flex-wrap items-center gap-2">
                <Loader2 className="animate-spin" size={16} />
                <span>livart 正在生图</span>
                <button
                  type="button"
                  onClick={() => setIsActiveTaskListOpen(isOpen => !isOpen)}
                  className="rounded-full border border-gray-200 bg-gray-50 px-2.5 py-1 text-xs font-black text-gray-900 transition-colors hover:bg-gray-100"
                  aria-expanded={isActiveTaskListOpen}
                >
                  {activeTaskCount} 个任务
                </button>
                <span className="text-xs font-black text-gray-400">可继续提交新任务</span>
              </div>
              {isActiveTaskListOpen && (
                <div className="absolute bottom-full left-0 z-30 mb-2 w-full min-w-[280px] rounded-2xl border border-gray-100 bg-white p-2 shadow-[0_18px_50px_-28px_rgba(0,0,0,0.45)]">
                  {sortedActiveTasks.map((task, index) => (
                    <div key={task.key} className="flex items-center justify-between gap-3 rounded-xl px-2 py-2 text-xs">
                      <span className="min-w-0 truncate font-black text-gray-700">
                        {task.label || `图片任务 ${index + 1}`}
                      </span>
                      <span className="shrink-0 tabular-nums font-black text-gray-400">
                        {task.status === 'completed'
                          ? `已完成 · 耗时 ${formatExecutionDuration(Math.max(0, (task.completedAt || timerNow) - task.startedAt))}`
                          : task.status === 'queued'
                            ? '排队中'
                            : `已执行 ${formatExecutionDuration(Math.max(0, timerNow - task.startedAt))}`}
                      </span>
                    </div>
                  ))}
                </div>
              )}
            </div>
          )}
        </div>

        <div className="p-3 bg-white border-t border-gray-100 space-y-3 md:p-4">
          <form ref={formRef} onSubmit={handleSubmit} className="relative">
            <div className="flex items-end gap-2 w-full pr-2 py-2 pl-3 bg-gray-50 border border-gray-200 rounded-2xl focus-within:ring-4 focus-within:ring-black/5 focus-within:border-black transition-all">
              <ImageMentionEditor
                value={inputValue}
                imageItems={completedImageItems}
                onChange={handleInputChange}
                disabled={false}
                placeholder={contextImage ? '输入对这张图的修改要求...' : '描述画面，或输入 @ 选择参考图...'}
                className="prompt-editor scrollbar-hide min-w-0 flex-1 min-h-[108px] max-h-40 overflow-y-auto overflow-x-hidden bg-transparent py-1.5 text-sm leading-6 font-medium outline-none whitespace-pre-wrap break-words"
                itemHint={() => '点击后插入稳定 ID 引用'}
                onSubmitShortcut={() => formRef.current?.requestSubmit()}
                focusSignal={inputFocusSignal}
              />
              <button
                type="submit"
                disabled={!inputValue.trim()}
                className="w-10 h-10 text-white rounded-xl hover:scale-105 active:scale-95 transition-all flex items-center justify-center shadow-lg shadow-black/10 flex-shrink-0 bg-black disabled:opacity-30"
              >
                <Send size={18} />
              </button>
            </div>

            <div className="mt-2 flex flex-wrap items-center gap-2 rounded-2xl border border-gray-100 bg-gray-50 px-2 py-2">
              <div ref={aspectRatioMenuRef} className="relative shrink-0">
                <button
                  type="button"
                  onClick={() => {
                    setIsAspectRatioMenuOpen(open => !open);
                    setIsResolutionMenuOpen(false);
                    setIsSkillMenuOpen(false);
                  }}
                  className="flex h-9 items-center gap-1.5 rounded-xl bg-white px-2 text-xs font-black text-zinc-700 transition-all hover:bg-indigo-50 hover:text-indigo-600"
                  title="选择画幅比例"
                >
                  <span className="text-[10px] uppercase tracking-widest text-gray-400">画幅</span>
                  <span className="flex h-5 w-7 shrink-0 items-center justify-center">
                    <span
                      className={`block rounded-[3px] border-2 ${
                        selectedAspectRatio === 'auto'
                          ? 'border-dashed border-gray-400'
                          : 'border-gray-700 bg-white'
                      }`}
                      style={getAspectRatioPreviewStyle(selectedAspectRatio)}
                    />
                  </span>
                  <span>{selectedAspectRatioOption.label}</span>
                  <ChevronDown size={13} className={`transition-transform ${isAspectRatioMenuOpen ? 'rotate-180' : ''}`} />
                </button>

                {isAspectRatioMenuOpen && (
                  <div className="absolute bottom-full left-0 z-50 mb-2 w-64 rounded-2xl border border-gray-100 bg-white p-2 shadow-xl shadow-black/10">
                    <div className="mb-1 px-2 text-[10px] font-black uppercase tracking-widest text-gray-400">选择画幅</div>
                    <div className="grid grid-cols-2 gap-1.5">
                      {IMAGE_ASPECT_RATIO_OPTIONS.map((option) => {
                        const isSelected = selectedAspectRatio === option.value;
                        return (
                          <button
                            key={option.value}
                            type="button"
                            title={option.title}
                            onClick={() => {
                              setSelectedAspectRatio(option.value);
                              setIsAspectRatioMenuOpen(false);
                            }}
                            className={`flex items-center gap-2 rounded-xl px-2 py-2 text-left text-[11px] font-black transition-all ${
                              isSelected
                                ? 'bg-black text-white'
                                : 'bg-gray-50 text-gray-600 hover:bg-indigo-50 hover:text-indigo-600'
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
                )}
              </div>

              <div ref={resolutionMenuRef} className="relative shrink-0">
                <button
                  type="button"
                  onClick={() => {
                    setIsResolutionMenuOpen(open => !open);
                    setIsAspectRatioMenuOpen(false);
                    setIsSkillMenuOpen(false);
                  }}
                  className="flex h-9 items-center gap-1.5 rounded-xl bg-white px-2 text-xs font-black text-zinc-700 transition-all hover:bg-indigo-50 hover:text-indigo-600"
                  title={selectedImageResolutionOption.title}
                >
                  <span className="text-[10px] uppercase tracking-widest text-gray-400">清晰度</span>
                  <span>{selectedImageResolutionOption.label}</span>
                  <ChevronDown size={13} className={`transition-transform ${isResolutionMenuOpen ? 'rotate-180' : ''}`} />
                </button>

                {isResolutionMenuOpen && (
                  <div className="absolute bottom-full left-0 z-50 mb-2 w-48 rounded-2xl border border-gray-100 bg-white p-2 shadow-xl shadow-black/10">
                    <div className="mb-1 px-2 text-[10px] font-black uppercase tracking-widest text-gray-400">选择清晰度</div>
                    <div className="space-y-1">
                      {IMAGE_RESOLUTION_OPTIONS.map(option => {
                        const isSelected = selectedImageResolution === option.value;
                        return (
                          <button
                            key={option.value}
                            type="button"
                            title={option.title}
                            onClick={() => {
                              setSelectedImageResolution(option.value);
                              setIsResolutionMenuOpen(false);
                            }}
                            className={`flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left transition-all ${
                              isSelected
                                ? 'bg-black text-white'
                                : 'bg-gray-50 text-gray-600 hover:bg-indigo-50 hover:text-indigo-600'
                            }`}
                          >
                            <span className="text-xs font-black">{option.label}</span>
                            <span className={`min-w-0 flex-1 truncate text-[10px] font-bold ${
                              isSelected ? 'text-white/70' : 'text-gray-400'
                            }`}>
                              {option.title}
                            </span>
                          </button>
                        );
                      })}
                    </div>
                  </div>
                )}
              </div>

              <div className="flex shrink-0 items-center gap-1 rounded-xl bg-white px-1 py-1">
                <span className="px-1 text-[10px] font-black uppercase tracking-widest text-gray-400">优化提示词</span>
                {[
                  { value: true, label: '是' },
                  { value: false, label: '否' }
                ].map(option => {
                  const isSelected = isPromptOptimizationEnabled === option.value;
                  return (
                    <label
                      key={option.label}
                      className={`cursor-pointer rounded-lg px-2.5 py-1 text-[11px] font-black transition-all ${
                        isSelected
                          ? 'bg-black text-white'
                          : 'text-zinc-600 hover:bg-indigo-50 hover:text-indigo-600'
                      }`}
                      title={option.value ? '发送前先优化提示词' : '直接使用当前输入的提示词'}
                    >
                      <input
                        type="radio"
                        name="sidebar-prompt-optimization"
                        className="sr-only"
                        checked={isPromptOptimizationEnabled === option.value}
                        onChange={() => setIsPromptOptimizationEnabled(option.value)}
                      />
                      {option.label}
                    </label>
                  );
                })}
              </div>

              <div ref={skillMenuRef} className="relative hidden min-w-0 flex-1 md:block">
                <button
                  type="button"
                  disabled={!ENABLE_EXTERNAL_SKILLS}
                  onClick={() => {
                    if (!ENABLE_EXTERNAL_SKILLS) return;
                    setIsSkillMenuOpen(open => !open);
                    setIsAspectRatioMenuOpen(false);
                    setIsResolutionMenuOpen(false);
                  }}
                  className={`flex h-9 w-full min-w-0 items-center gap-2 rounded-xl px-2 text-left transition-all ${
                    ENABLE_EXTERNAL_SKILLS
                      ? 'hover:bg-white'
                      : 'cursor-not-allowed opacity-45'
                  }`}
                  title={ENABLE_EXTERNAL_SKILLS ? externalSkillLoadError || '选择一个外部 Skill' : '外部 Skill 暂停使用'}
                >
                  <span className="shrink-0 text-[10px] font-black uppercase tracking-widest text-gray-400">Skill</span>
                  <span className="min-w-0 flex-1 truncate text-xs font-bold text-zinc-700">
                    {ENABLE_EXTERNAL_SKILLS
                      ? selectedExternalSkill ? selectedExternalSkill.name : '不使用外部 Skill'
                      : '外部 Skill 暂不可用'}
                    {ENABLE_EXTERNAL_SKILLS && selectedExternalSkill?.version ? ` · ${selectedExternalSkill.version}` : ''}
                  </span>
                  <ChevronDown size={13} className={`shrink-0 text-gray-400 transition-transform ${isSkillMenuOpen ? 'rotate-180' : ''}`} />
                </button>

                {ENABLE_EXTERNAL_SKILLS && isSkillMenuOpen && (
                  <div className="absolute bottom-full right-0 z-50 mb-2 w-72 rounded-2xl border border-gray-100 bg-white p-2 shadow-xl shadow-black/10">
                    <div className="mb-1 px-2 text-[10px] font-black uppercase tracking-widest text-gray-400">选择 Skill</div>
                    <button
                      type="button"
                      onClick={() => {
                        setSelectedExternalSkillId('');
                        setIsSkillMenuOpen(false);
                      }}
                      className={`flex w-full items-center gap-2 rounded-xl px-3 py-2 text-left text-xs font-bold transition-all ${
                        selectedExternalSkillId
                          ? 'text-gray-500 hover:bg-gray-50 hover:text-gray-900'
                          : 'bg-black text-white'
                      }`}
                    >
                      <span className="min-w-0 flex-1 truncate">不使用外部 Skill</span>
                    </button>
                    {externalSkills.map(skill => {
                      const isSelected = selectedExternalSkillId === skill.id;
                      return (
                        <div
                          key={skill.id}
                          className={`mt-1 flex items-center gap-1 rounded-xl transition-all ${
                            isSelected
                              ? 'bg-black text-white'
                              : 'bg-gray-50 text-gray-600 hover:bg-indigo-50 hover:text-indigo-600'
                          }`}
                        >
                          <button
                            type="button"
                            onClick={() => {
                              setSelectedExternalSkillId(skill.id);
                              setIsSkillMenuOpen(false);
                            }}
                            className="min-w-0 flex-1 px-3 py-2 text-left"
                            title={skill.description || skill.name}
                          >
                            <span className="block truncate text-xs font-black">
                              {skill.name}{skill.version ? ` · ${skill.version}` : ''}
                            </span>
                            {skill.description && (
                              <span className={`mt-0.5 block truncate text-[10px] font-medium ${isSelected ? 'text-white/70' : 'text-gray-400'}`}>
                                {skill.description}
                              </span>
                            )}
                          </button>
                          {skill.sourceUrl && (
                            <a
                              href={skill.sourceUrl}
                              target="_blank"
                              rel="noreferrer"
                              onClick={(event) => event.stopPropagation()}
                              className={`mr-2 flex h-8 w-8 shrink-0 items-center justify-center rounded-lg transition-all ${
                                isSelected
                                  ? 'text-white/80 hover:bg-white/15 hover:text-white'
                                  : 'text-gray-400 hover:bg-white hover:text-gray-900'
                              }`}
                              title="打开 GitHub 页面"
                            >
                              <Github size={15} />
                            </a>
                          )}
                        </div>
                      );
                    })}
                  </div>
                )}
              </div>
            </div>
            {ENABLE_EXTERNAL_SKILLS && externalSkillLoadError && (
              <p className="mt-1 hidden px-1 text-[10px] font-medium text-amber-600 md:block">{externalSkillLoadError}</p>
            )}
          </form>
        </div>
      </div>
      {renderProductPosterModal()}
      {renderImageViewer()}
    </>
  );
};

export default Sidebar;
