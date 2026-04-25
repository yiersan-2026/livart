import type { CanvasItem } from '../types';

export const getCanvasImageSrc = (item: CanvasItem) => {
  return item.previewContent || item.content;
};

export const getThumbnailImageSrc = (item: CanvasItem) => {
  return item.thumbnailContent || item.previewContent || item.content;
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
