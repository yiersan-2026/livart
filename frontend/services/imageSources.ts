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
