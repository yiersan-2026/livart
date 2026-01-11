const CONFIG_KEY = 'lingjiang_api_config';

export const AVAILABLE_MODELS = [
  'gemini-3-pro-image-preview',
  'gemini-2.5-flash-image',
  'gemini-2.5-pro-image'
];

export interface ApiConfig {
  baseUrl: string;
  apiKey: string;
  model: string;
}

export const getApiConfig = (): ApiConfig => {
  const stored = localStorage.getItem(CONFIG_KEY);
  if (stored) {
    const parsed = JSON.parse(stored);
    return {
      baseUrl: parsed.baseUrl || '',
      apiKey: parsed.apiKey || '',
      model: parsed.model || 'gemini-3-pro-image-preview'
    };
  }
  return { baseUrl: '', apiKey: '', model: 'gemini-3-pro-image-preview' };
};

export const saveApiConfig = (config: ApiConfig) => {
  localStorage.setItem(CONFIG_KEY, JSON.stringify(config));
};

export const hasApiConfig = (): boolean => {
  const config = getApiConfig();
  return !!(config.baseUrl && config.apiKey);
};
