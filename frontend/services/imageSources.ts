import type { CanvasItem } from '../types';

type ImageVariant = 'content' | 'preview' | 'thumbnail' | `view/${number}`;

export const CANVAS_IMAGE_WIDTH_TIERS = [512, 1024, 2048] as const;

const isDataImageUrl = (value: unknown): value is string => {
  return typeof value === 'string' && value.startsWith('data:image/');
};

export const hasUsableImageSource = (item: CanvasItem) => (
  item.type === 'image' &&
  !!(item.content || item.previewContent || item.thumbnailContent || item.assetId)
);

const getAssetVariantUrlFromValue = (value: unknown, variant: ImageVariant) => {
  if (typeof value !== 'string') return '';
  const match = value.match(/\/api\/assets\/([^/]+)\/(?:content|preview|thumbnail|view\/\d+)(?:[?#].*)?$/);
  if (!match) return '';
  return `/api/assets/${match[1]}/${variant}`;
};

const getAssetVariantUrl = (item: CanvasItem, variant: ImageVariant) => {
  if (item.assetId) {
    return `/api/assets/${encodeURIComponent(item.assetId)}/${variant}`;
  }
  return (
    getAssetVariantUrlFromValue(item.previewContent, variant) ||
    getAssetVariantUrlFromValue(item.thumbnailContent, variant) ||
    getAssetVariantUrlFromValue(item.content, variant)
  );
};

export const getCanvasImageWidthTierSize = (item: CanvasItem, zoom = 1) => {
  const safeZoom = Math.max(0.01, Number.isFinite(zoom) ? zoom : 1);
  const devicePixelRatio = typeof window === 'undefined' ? 1 : Math.max(1, window.devicePixelRatio || 1);
  const visibleWidth = Math.max(1, Number(item.width) || 1) * safeZoom * devicePixelRatio;
  return CANVAS_IMAGE_WIDTH_TIERS.find(width => width >= visibleWidth) || CANVAS_IMAGE_WIDTH_TIERS[CANVAS_IMAGE_WIDTH_TIERS.length - 1];
};

export const getCanvasImageTierSize = getCanvasImageWidthTierSize;

export const getCanvasImageSrc = (item: CanvasItem, zoom = 1) => {
  const tierSize = getCanvasImageWidthTierSize(item, zoom);
  return (
    getAssetVariantUrl(item, `view/${tierSize}`) ||
    item.previewContent ||
    (isDataImageUrl(item.content) ? item.content : '')
  );
};

export const getLargestCanvasImageSrc = (item: CanvasItem) => {
  const largestTierSize = CANVAS_IMAGE_WIDTH_TIERS[CANVAS_IMAGE_WIDTH_TIERS.length - 1];
  return (
    getAssetVariantUrl(item, `view/${largestTierSize}`) ||
    item.previewContent ||
    item.thumbnailContent ||
    (isDataImageUrl(item.content) ? item.content : '')
  );
};

export const getThumbnailImageSrc = (item: CanvasItem) => {
  return (
    getAssetVariantUrl(item, 'thumbnail') ||
    item.thumbnailContent ||
    item.previewContent ||
    (isDataImageUrl(item.content) ? item.content : '')
  );
};

export const getOriginalImageSrc = (item: CanvasItem) => {
  return item.content;
};

export const getImagePreviewFrame = (
  item: CanvasItem,
  maxWidth: number,
  maxHeight: number
) => {
  const imageWidth = Math.max(1, Number(item.width) || 1);
  const imageHeight = Math.max(1, Number(item.height) || 1);
  const ratio = imageWidth / imageHeight;

  let width = maxWidth;
  let height = width / ratio;

  if (height > maxHeight) {
    height = maxHeight;
    width = height * ratio;
  }

  return {
    width: Math.max(1, Math.round(width)),
    height: Math.max(1, Math.round(height))
  };
};

export const getImagePreviewFitStyle = (
  item: CanvasItem,
  maxWidth: number,
  maxHeight: number
) => {
  const frame = getImagePreviewFrame(item, maxWidth, maxHeight);
  return {
    width: `${frame.width}px`,
    height: `${frame.height}px`
  };
};
