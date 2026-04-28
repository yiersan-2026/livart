import type { ExternalSkillSummary } from '../types';
import { authHeaders } from './auth';

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

export const loadExternalSkills = async (): Promise<ExternalSkillSummary[]> => {
  const response = await fetch('/api/skills', {
    headers: {
      Accept: 'application/json',
      ...authHeaders()
    }
  });
  const payload = await response.json().catch(() => null) as ApiResponse<ExternalSkillSummary[]> | null;
  if (!response.ok || !payload?.success) {
    throw new Error(payload?.error?.message || `加载外部 Skill 失败：${response.status}`);
  }
  return Array.isArray(payload.data) ? payload.data : [];
};
