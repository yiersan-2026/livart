import type { CanvasItem } from '../types';
import { authHeaders } from './auth';
import { ensureCanvasImageAsset, getCanvasItemAssetId } from './canvasPersistence';
import { getOriginalImageSrc } from './imageSources';

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

interface ImageExportResponse {
  exportId: string;
  filename: string;
  downloadUrl: string;
  expiresAt: string;
}

interface ImageExportItemRequest {
  assetId: string;
  filename: string;
}

export type CanvasExportScope = 'selected' | 'final' | 'derived' | 'delivery' | 'all';

const unwrapApiResponse = async <T>(response: Response): Promise<T> => {
  const payload = await response.json().catch(() => null) as ApiResponse<T> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.error?.message || `导出请求失败：${response.status}`);
  }
  if (payload.data === undefined) {
    throw new Error('导出响应为空');
  }
  return payload.data;
};

const sanitizeFilename = (value: string) => {
  const normalized = value.trim().replace(/[\\/:*?"<>|]+/g, '-').replace(/\s+/g, '-');
  return normalized || 'livart-image';
};

const createTimestamp = () => {
  return new Date().toISOString().replace(/[:.]/g, '-');
};

const getImageFileExtension = (source: string) => {
  if (source.startsWith('data:image/')) {
    const mime = source.slice('data:image/'.length, source.indexOf(';'));
    return mime === 'jpeg' ? 'jpg' : mime || 'png';
  }

  const cleanUrl = source.split('?')[0];
  const match = cleanUrl.match(/\.([a-zA-Z0-9]+)$/);
  return match?.[1]?.toLowerCase() || 'png';
};

const downloadBlob = (blob: Blob, filename: string) => {
  const url = URL.createObjectURL(blob);
  const link = document.createElement('a');
  link.href = url;
  link.download = filename;
  document.body.appendChild(link);
  link.click();
  link.remove();
  window.setTimeout(() => URL.revokeObjectURL(url), 1000);
};

const isDownloadableImage = (item: CanvasItem) => (
  item.type === 'image' &&
  item.status === 'completed' &&
  !!item.content
);

const getDownloadImageCandidates = (
  items: CanvasItem[],
  selectedIds: string[],
  scope: CanvasExportScope
) => {
  const completedImages = items.filter(isDownloadableImage);
  const selectedImages = selectedIds
    .map(id => items.find(item => item.id === id))
    .filter((item): item is CanvasItem => !!item && isDownloadableImage(item));

  if (scope === 'selected') {
    if (selectedImages.length > 0) return selectedImages;
    throw new Error('请先选择要下载的成品图片');
  }

  if (scope === 'final') {
    const parentIds = new Set(completedImages.map(item => item.parentId).filter(Boolean));
    return completedImages.filter(item => !parentIds.has(item.id));
  }

  if (scope === 'derived') {
    return completedImages.filter(item => !!item.parentId);
  }

  if (scope === 'all') {
    return completedImages;
  }

  return completedImages;
};

const getExportScopeFilenamePart = (scope: CanvasExportScope) => {
  switch (scope) {
    case 'selected':
      return 'selected';
    case 'final':
      return 'final';
    case 'derived':
      return 'derived';
    case 'all':
      return 'all';
    default:
      return 'delivery';
  }
};

const createImageExport = async (images: ImageExportItemRequest[], filename: string) => {
  const response = await fetch('/api/exports/images', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({ images, filename })
  });

  return unwrapApiResponse<ImageExportResponse>(response);
};

const downloadExportZip = async (downloadUrl: string, filename: string) => {
  const response = await fetch(downloadUrl, {
    headers: {
      ...authHeaders()
    }
  });

  if (!response.ok) {
    const payload = await response.json().catch(() => null) as ApiResponse<unknown> | null;
    throw new Error(payload?.error?.message || `压缩包下载失败：${response.status}`);
  }

  downloadBlob(await response.blob(), filename);
};

export const exportCanvasProjectImage = async (
  items: CanvasItem[],
  selectedIds: string[],
  projectTitle: string,
  scope: CanvasExportScope = 'delivery'
) => {
  const candidates = getDownloadImageCandidates(items, selectedIds, scope);

  if (!candidates.length) {
    throw new Error(scope === 'derived' ? '当前没有可下载的派生图片' : '当前没有可下载的成品图片');
  }

  const timestamp = createTimestamp();
  const exportImages = await Promise.all(candidates.map(async (imageItem, index) => {
    const source = getOriginalImageSrc(imageItem);
    const persistedImageItem = await ensureCanvasImageAsset(imageItem);
    const assetId = getCanvasItemAssetId(persistedImageItem);
    if (!assetId) {
      throw new Error('成品图片缺少可打包的资源 ID');
    }

    const extension = getImageFileExtension(source);
    const filenamePrefix = sanitizeFilename(imageItem.label || `${projectTitle || 'livart-image'}-${index + 1}`);
    return {
      assetId,
      filename: `${filenamePrefix}-${imageItem.id}.${extension}`
    };
  }));

  const zipFilenamePrefix = sanitizeFilename(projectTitle || 'livart-images');
  const zipFilename = `${zipFilenamePrefix}-${getExportScopeFilenamePart(scope)}-${timestamp}.zip`;
  const imageExport = await createImageExport(exportImages, zipFilename);
  await downloadExportZip(imageExport.downloadUrl, imageExport.filename);
};
