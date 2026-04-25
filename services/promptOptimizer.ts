export type PromptOptimizeMode = 'text-to-image' | 'image-to-image';

export const optimizePrompt = async (prompt: string, mode: PromptOptimizeMode) => {
  const response = await fetch('/api/prompts/optimize', {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json'
    },
    body: JSON.stringify({ prompt, mode })
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
