import React, { useEffect, useMemo, useState } from 'react';
import { Eye, EyeOff, X, Save } from 'lucide-react';
import {
  getApiConfig,
  saveApiConfig,
  ApiConfig,
  AVAILABLE_CHAT_MODELS,
  AVAILABLE_MODELS,
  DEFAULT_API_CONFIG,
  buildImageApiUrls,
  joinUrl,
  normalizeApiConfig
} from '../services/config';

interface ConfigModalProps {
  isOpen: boolean;
  onClose: () => void;
  onSaved?: () => void;
  required?: boolean;
}

const ConfigModal: React.FC<ConfigModalProps> = ({ isOpen, onClose, onSaved, required = false }) => {
  const [config, setConfig] = useState<ApiConfig>(DEFAULT_API_CONFIG);
  const [error, setError] = useState('');
  const [showApiKey, setShowApiKey] = useState(false);
  const [isSaving, setIsSaving] = useState(false);

  useEffect(() => {
    if (isOpen) {
      setConfig(getApiConfig());
      setError('');
      setShowApiKey(false);
      setIsSaving(false);
    }
  }, [isOpen]);

  const previewUrls = useMemo(() => buildImageApiUrls(config.baseUrl), [config.baseUrl]);
  const chatResponsesUrl = useMemo(() => joinUrl(config.baseUrl, 'responses'), [config.baseUrl]);

  const handleSave = async () => {
    const normalizedConfig = normalizeApiConfig(config);
    if (!normalizedConfig.baseUrl || !normalizedConfig.apiKey || !normalizedConfig.model || !normalizedConfig.chatModel) {
      setError('请填写中转站 Base URL、API Key、生图模型和对话模型');
      return;
    }

    setError('');
    setIsSaving(true);
    try {
      await saveApiConfig(normalizedConfig);
      onSaved?.();
      onClose();
    } catch (saveError) {
      setError(saveError instanceof Error ? saveError.message : '保存配置失败，请稍后重试');
    } finally {
      setIsSaving(false);
    }
  };

  if (!isOpen) return null;

  return (
    <div className="fixed inset-0 bg-black/50 flex items-center justify-center z-50">
      <div className="bg-white rounded-2xl shadow-2xl w-[500px] max-h-[88vh] overflow-hidden">
        <div className="flex items-center justify-between px-6 py-4 border-b border-gray-100">
          <div>
            <h2 className="font-bold text-lg">中转站配置</h2>
            {required && (
              <p className="mt-1 text-xs font-bold text-gray-400">首次登录后需要先完成配置，系统会自动拼接调用地址。</p>
            )}
          </div>
          {!required && (
            <button onClick={onClose} className="p-1 hover:bg-gray-100 rounded-lg transition-colors">
              <X size={18} className="text-gray-400" />
            </button>
          )}
        </div>

        <div className="p-6 space-y-4 max-h-[calc(88vh-132px)] overflow-y-auto">
          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">中转站 Base URL</label>
            <input
              type="text"
              value={config.baseUrl}
              onChange={(event) => setConfig({ ...config, baseUrl: event.target.value })}
              placeholder="https://www.kuyaoapi.com/v1/"
              className="w-full px-4 py-3 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-black/10 focus:border-gray-300 transition-all"
            />
            <p className="text-xs text-gray-400 mt-1">只填基础地址即可，例如以 `/v1` 结尾的 OpenAI 兼容中转站地址。</p>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">API Key</label>
            <div className="relative">
              <input
                type={showApiKey ? 'text' : 'password'}
                value={config.apiKey}
                onChange={(event) => setConfig({ ...config, apiKey: event.target.value })}
                placeholder="sk-..."
                className="w-full px-4 py-3 pr-12 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-black/10 focus:border-gray-300 transition-all"
              />
              <button
                type="button"
                onClick={() => setShowApiKey(prev => !prev)}
                className="absolute right-3 top-1/2 -translate-y-1/2 p-1.5 rounded-lg text-gray-400 hover:bg-gray-100 hover:text-gray-700 transition-colors"
                title={showApiKey ? '隐藏 API Key' : '显示 API Key 原文'}
              >
                {showApiKey ? <EyeOff size={17} /> : <Eye size={17} />}
              </button>
            </div>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">生图模型</label>
            <select
              value={config.model}
              onChange={(event) => setConfig({ ...config, model: event.target.value })}
              className="w-full px-4 py-3 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-black/10 focus:border-gray-300 transition-all bg-white"
            >
              {AVAILABLE_MODELS.map((model) => (
                <option key={model} value={model}>{model}</option>
              ))}
            </select>
          </div>

          <div>
            <label className="block text-sm font-medium text-gray-700 mb-2">对话模型</label>
            <select
              value={config.chatModel}
              onChange={(event) => setConfig({ ...config, chatModel: event.target.value })}
              className="w-full px-4 py-3 border border-gray-200 rounded-xl focus:outline-none focus:ring-2 focus:ring-black/10 focus:border-gray-300 transition-all bg-white"
            >
              {AVAILABLE_CHAT_MODELS.map((model) => (
                <option key={model} value={model}>{model}</option>
              ))}
            </select>
            <p className="text-xs text-gray-400 mt-1">用于右侧对话和图片下方输入框的提示词自动优化。</p>
          </div>

          <div className="rounded-2xl border border-gray-100 bg-gray-50 p-4 text-xs text-gray-500 space-y-2">
            <div className="font-black text-gray-700">自动拼接地址</div>
            <div className="break-all">文生图：{previewUrls.textToImageUrl || '填写 Base URL 后自动生成'}</div>
            <div className="break-all">图生图：{previewUrls.imageToImageUrl || '填写 Base URL 后自动生成'}</div>
            <div className="break-all">对话：{chatResponsesUrl || '填写 Base URL 后自动生成'}</div>
          </div>

          {error && (
            <div className="rounded-2xl border border-red-100 bg-red-50 px-4 py-3 text-sm font-bold text-red-500">
              {error}
            </div>
          )}
        </div>

        <div className="px-6 py-4 bg-gray-50 flex justify-end gap-3">
          {!required && (
            <button
              onClick={onClose}
              disabled={isSaving}
              className="px-4 py-2 text-sm text-gray-600 hover:bg-gray-100 rounded-xl transition-colors"
            >
              取消
            </button>
          )}
          <button
            onClick={handleSave}
            disabled={isSaving}
            className="px-4 py-2 text-sm bg-black text-white rounded-xl hover:opacity-90 transition-all flex items-center gap-2 disabled:opacity-40"
          >
            <Save size={14} />
            {isSaving ? '保存中...' : '保存配置'}
          </button>
        </div>
      </div>
    </div>
  );
};

export default ConfigModal;
