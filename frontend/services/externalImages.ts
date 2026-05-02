import { authHeaders } from './auth';

export interface ExternalImageCandidate {
  id: string;
  url: string;
  thumbnailUrl: string;
  title?: string;
  formatLabel?: string;
  mimeType?: string;
  width?: number;
  height?: number;
  fileSizeBytes?: number | null;
  watermarked?: boolean | null;
  sortOrder?: number | null;
}

export interface ImportedExternalImage {
  assetId: string;
  urlPath: string;
  previewUrlPath?: string;
  thumbnailUrlPath?: string;
  originalFilename?: string;
  mimeType?: string;
  sizeBytes: number;
  width?: number;
  height?: number;
  createdAt?: string;
  sourceUrl: string;
}

export interface ExternalImageParseHistoryItem {
  sourceUrl: string;
  sourceHost?: string;
  imageCount: number;
  lastParsedAt?: string;
}

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

interface SearchExternalImagesResponse {
  sourceUrl: string;
  images: ExternalImageCandidate[];
}

interface ExternalImageParseHistoryResponse {
  items: ExternalImageParseHistoryItem[];
}

const unwrapApiResponse = async <T>(response: Response): Promise<T> => {
  const payload = await response.json().catch(() => null) as ApiResponse<T> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.error?.message || `社交媒体图片请求失败：${response.status}`);
  }
  if (payload.data === undefined) {
    throw new Error('社交媒体图片响应为空');
  }
  return payload.data;
};

export const searchExternalImages = async (url: string) => {
  const response = await fetch('/api/external/images', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({ url })
  });
  const payload = await unwrapApiResponse<SearchExternalImagesResponse>(response);
  return payload.images || [];
};

export const loadExternalImageParseHistory = async () => {
  const response = await fetch('/api/external/images/history', {
    headers: {
      Accept: 'application/json',
      ...authHeaders()
    }
  });
  const payload = await unwrapApiResponse<ExternalImageParseHistoryResponse>(response);
  return payload.items || [];
};

export const importExternalImage = async (
  candidate: ExternalImageCandidate,
  options: { canvasId?: string } = {}
) => {
  const response = await fetch('/api/external/images/import', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({
      url: candidate.url,
      filename: candidate.formatLabel || candidate.title || 'social-image',
      canvasId: options.canvasId || undefined
    })
  });
  return unwrapApiResponse<ImportedExternalImage>(response);
};
