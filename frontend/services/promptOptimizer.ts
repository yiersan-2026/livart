import { getApiConfig } from './config';
import { authHeaders } from './auth';

export type PromptOptimizeMode = 'text-to-image' | 'image-to-image';

export const optimizePrompt = async (prompt: string, mode: PromptOptimizeMode) => {
  const config = getApiConfig();

  const response = await fetch('/api/prompts/optimize', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      ...authHeaders(),
      ...(config.apiKey ? { 'X-Livart-Api-Key': config.apiKey } : {})
    },
    body: JSON.stringify({
      prompt,
      mode,
      model: config.chatModel,
      baseUrl: config.baseUrl
    })
  });

  const data = await response.json().catch(() => null);

  if (!response.ok) {
    throw new Error(data?.error || data?.detail || '提示词优化失败');
  }

  if (!data?.optimizedPrompt) {
    throw new Error('提示词优化结果为空');
  }

  return String(data.optimizedPrompt).trim();
};
