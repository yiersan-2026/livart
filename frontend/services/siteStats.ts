import { authHeaders } from './auth';

export interface SiteStatsOverview {
  userCount: number;
  generatedImageCount: number;
  activeImageJobCount: number;
  memory: {
    usedBytes: number;
    freeBytes: number;
    totalBytes: number;
    usedPercent: number;
  };
  processor: {
    usedPercent: number;
    availableProcessors: number;
  };
  disk: {
    path: string;
    usedBytes: number;
    freeBytes: number;
    totalBytes: number;
    usedPercent: number;
  };
}

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

export const loadSiteStatsOverview = async (signal?: AbortSignal) => {
  const response = await fetch('/api/stats/overview', {
    signal,
    headers: {
      Accept: 'application/json',
      ...authHeaders()
    }
  });
  const payload = await response.json().catch(() => null) as ApiResponse<SiteStatsOverview> | null;
  if (!response.ok || !payload?.success || !payload.data) {
    throw new Error(payload?.error?.message || `统计数据加载失败：${response.status}`);
  }
  return payload.data;
};
