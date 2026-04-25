const CONFIG_KEY = 'livart_api_config';

export const joinUrl = (baseUrl: string, path: string) => {
  if (!baseUrl) return '';
  return `${baseUrl.replace(/\/+$/, '')}/${path.replace(/^\/+/, '')}`;
};

const normalizeBaseUrl = (baseUrl: string | undefined) => (baseUrl || '').trim().replace(/\/+$/, '');

const TEXT_TO_IMAGE_PATH = 'images/generations';
const IMAGE_TO_IMAGE_PATH = 'images/edits';

export const AVAILABLE_MODELS = [
  'gpt-image-2'
];

export const AVAILABLE_CHAT_MODELS = [
  'gpt-5.5',
  'gpt-5.4'
];

export interface ApiConfig {
  baseUrl: string;
  textToImageUrl: string;
  imageToImageUrl: string;
  apiKey: string;
  model: string;
  chatModel: string;
}

export const buildImageApiUrls = (baseUrl: string) => {
  const normalizedBaseUrl = normalizeBaseUrl(baseUrl);
  return {
    textToImageUrl: joinUrl(normalizedBaseUrl, TEXT_TO_IMAGE_PATH),
    imageToImageUrl: joinUrl(normalizedBaseUrl, IMAGE_TO_IMAGE_PATH)
  };
};

const defaultBaseUrl = normalizeBaseUrl(process.env.IMAGE_API_BASE_URL || '');

export const DEFAULT_API_CONFIG: ApiConfig = {
  baseUrl: defaultBaseUrl,
  ...buildImageApiUrls(defaultBaseUrl),
  apiKey: process.env.IMAGE_API_KEY || '',
  model: process.env.IMAGE_API_MODEL || 'gpt-image-2',
  chatModel: process.env.PROMPT_OPTIMIZER_MODEL || process.env.CHAT_API_MODEL || 'gpt-5.5'
};

export const normalizeApiConfig = (config: Partial<ApiConfig>): ApiConfig => {
  const baseUrl = normalizeBaseUrl(config.baseUrl || DEFAULT_API_CONFIG.baseUrl);
  const imageApiUrls = buildImageApiUrls(baseUrl);
  const imageModel = AVAILABLE_MODELS.includes(config.model || '')
    ? config.model || DEFAULT_API_CONFIG.model
    : DEFAULT_API_CONFIG.model;
  const chatModel = AVAILABLE_CHAT_MODELS.includes(config.chatModel || '')
    ? config.chatModel || DEFAULT_API_CONFIG.chatModel
    : DEFAULT_API_CONFIG.chatModel;

  return {
    baseUrl,
    ...imageApiUrls,
    apiKey: (config.apiKey || DEFAULT_API_CONFIG.apiKey).trim(),
    model: imageModel,
    chatModel
  };
};

export const getApiConfig = (): ApiConfig => {
  const stored = localStorage.getItem(CONFIG_KEY);
  if (stored) {
    try {
      return normalizeApiConfig(JSON.parse(stored));
    } catch {
      localStorage.removeItem(CONFIG_KEY);
    }
  }
  return normalizeApiConfig(DEFAULT_API_CONFIG);
};

export const saveApiConfig = (config: ApiConfig) => {
  localStorage.setItem(CONFIG_KEY, JSON.stringify(normalizeApiConfig(config)));
};

export const hasApiConfig = (): boolean => {
  const config = getApiConfig();
  return !!(config.baseUrl && config.apiKey && config.model && config.chatModel);
};

export const hasStoredApiConfig = (): boolean => {
  const stored = localStorage.getItem(CONFIG_KEY);
  if (!stored) return false;
  try {
    const config = normalizeApiConfig(JSON.parse(stored));
    return !!(config.baseUrl && config.apiKey && config.model && config.chatModel);
  } catch {
    return false;
  }
};
