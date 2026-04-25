const CONFIG_KEY = 'lingjiang_api_config';

const joinUrl = (baseUrl: string, path: string) => {
  if (!baseUrl) return '';
  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`;
};

const LEGACY_GEMINI_MODELS = [
  'gemini-3-pro-image-preview',
  'gemini-2.5-flash-image',
  'gemini-2.5-pro-image'
];

export const AVAILABLE_MODELS = [
  'gpt-image-2',
  ...LEGACY_GEMINI_MODELS
];

export interface ApiConfig {
  baseUrl: string;
  textToImageUrl: string;
  imageToImageUrl: string;
  apiKey: string;
  model: string;
}

export const DEFAULT_API_CONFIG: ApiConfig = {
  baseUrl: process.env.IMAGE_API_BASE_URL || '',
  textToImageUrl: process.env.TEXT_TO_IMAGE_API_URL || process.env.IMAGE_TEXT_TO_IMAGE_URL || joinUrl(process.env.IMAGE_API_BASE_URL || '', 'images/generations'),
  imageToImageUrl: process.env.IMAGE_TO_IMAGE_API_URL || process.env.IMAGE_IMAGE_TO_IMAGE_URL || joinUrl(process.env.IMAGE_API_BASE_URL || '', 'images/edits'),
  apiKey: process.env.IMAGE_API_KEY || '',
  model: process.env.IMAGE_API_MODEL || 'gpt-image-2'
};

const normalizeApiConfig = (config: Partial<ApiConfig>): ApiConfig => {
  const isLegacyStoredConfig = !config.textToImageUrl && !config.imageToImageUrl && !!config.model && LEGACY_GEMINI_MODELS.includes(config.model);
  const prefersLocalProxy = DEFAULT_API_CONFIG.textToImageUrl.startsWith('/api/') && DEFAULT_API_CONFIG.imageToImageUrl.startsWith('/api/');
  const baseUrl = config.baseUrl || DEFAULT_API_CONFIG.baseUrl;
  const textToImageUrl = prefersLocalProxy && config.textToImageUrl?.startsWith('http') ? DEFAULT_API_CONFIG.textToImageUrl : config.textToImageUrl;
  const imageToImageUrl = prefersLocalProxy && config.imageToImageUrl?.startsWith('http') ? DEFAULT_API_CONFIG.imageToImageUrl : config.imageToImageUrl;
  return {
    baseUrl,
    textToImageUrl: textToImageUrl || DEFAULT_API_CONFIG.textToImageUrl || joinUrl(baseUrl, 'images/generations'),
    imageToImageUrl: imageToImageUrl || DEFAULT_API_CONFIG.imageToImageUrl || joinUrl(baseUrl, 'images/edits'),
    apiKey: config.apiKey || DEFAULT_API_CONFIG.apiKey,
    model: isLegacyStoredConfig ? DEFAULT_API_CONFIG.model : (config.model || DEFAULT_API_CONFIG.model)
  };
};

export const getApiConfig = (): ApiConfig => {
  const stored = localStorage.getItem(CONFIG_KEY);
  if (stored) {
    const parsed = JSON.parse(stored);
    return normalizeApiConfig(parsed);
  }
  return DEFAULT_API_CONFIG;
};

export const saveApiConfig = (config: ApiConfig) => {
  localStorage.setItem(CONFIG_KEY, JSON.stringify(config));
};

export const hasApiConfig = (): boolean => {
  const config = getApiConfig();
  return !!(config.apiKey && (config.textToImageUrl || config.imageToImageUrl || config.baseUrl));
};
