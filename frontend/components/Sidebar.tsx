import React, { useEffect, useMemo, useRef } from 'react';
import type { ChatMessage, CanvasItem, ImageAspectRatio } from '../types';
import { Loader2, Hammer, Send } from 'lucide-react';
import { IMAGE_ASPECT_RATIO_OPTIONS } from '../services/imageSizing';
import { getImagePreviewFitStyle, getThumbnailImageSrc } from '../services/imageSources';
import {
  getImageReferenceDisplayText,
  insertImageMention,
  resolveMentionedImageReferences,
  tokenizeImageReferenceText
} from '../services/imageReferences';
import ImageMentionEditor from './ImageMentionEditor';

interface SidebarProps {
  messages: ChatMessage[];
  isThinking: boolean;
  onSendMessage: (text: string, aspectRatio: ImageAspectRatio) => void;
  contextImage: CanvasItem | null;
  promptSeed?: { id: string; imageId: string; prompt?: string } | null;
  inputResetKey?: number;
  imageItems: CanvasItem[];
  onSelectContextImage: (item: CanvasItem) => void;
  onClearContextImage: () => void;
  onNavigateToImage: (item: CanvasItem) => void;
}

const Sidebar: React.FC<SidebarProps> = ({ messages, isThinking, onSendMessage, contextImage, promptSeed, inputResetKey = 0, imageItems, onClearContextImage, onNavigateToImage }) => {
  const [inputValue, setInputValue] = React.useState('');
  const [selectedAspectRatio, setSelectedAspectRatio] = React.useState<ImageAspectRatio>('auto');
  const scrollRef = useRef<HTMLDivElement>(null);
  const formRef = useRef<HTMLFormElement>(null);
  const lastContextImageIdRef = useRef<string | null>(null);

  const completedImageItems = useMemo(
    () => imageItems.filter(item => item.type === 'image' && item.status === 'completed' && !!item.content),
    [imageItems]
  );
  const imageItemsById = useMemo(
    () => new Map(imageItems.map(item => [item.id, item])),
    [imageItems]
  );

  useEffect(() => {
    setInputValue('');
    lastContextImageIdRef.current = null;
  }, [inputResetKey]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, isThinking]);

  useEffect(() => {
    const currentContextImageId = contextImage?.id ?? null;
    if (currentContextImageId === lastContextImageIdRef.current) return;

    lastContextImageIdRef.current = currentContextImageId;
    if (!contextImage) return;

    setInputValue(prevValue => {
      const mentionedImages = resolveMentionedImageReferences(prevValue, completedImageItems);
      if (mentionedImages.some(item => item.id === contextImage.id)) return prevValue;
      return insertImageMention(prevValue.trimEnd(), contextImage, completedImageItems);
    });
  }, [completedImageItems, contextImage]);

  useEffect(() => {
    if (!promptSeed) return;

    const targetImage = completedImageItems.find(item => item.id === promptSeed.imageId);
    if (!targetImage) return;

    lastContextImageIdRef.current = targetImage.id;
    const mention = insertImageMention('', targetImage, completedImageItems);
    setInputValue(promptSeed.prompt ? `${mention}${promptSeed.prompt}` : mention);
  }, [completedImageItems, promptSeed]);

  const handleInputChange = (nextValue: string) => {
    setInputValue(nextValue);

    if (!contextImage) return;

    const mentionedImages = resolveMentionedImageReferences(nextValue, completedImageItems);
    if (!mentionedImages.some(item => item.id === contextImage.id)) {
      lastContextImageIdRef.current = null;
      onClearContextImage();
    }
  };

  const isInputBusy = isThinking;

  const handleSubmit = (event: React.FormEvent) => {
    event.preventDefault();
    const prompt = inputValue.trim();
    if (!prompt || isInputBusy) return;

    onSendMessage(prompt, selectedAspectRatio);
    setInputValue('');
  };

  const renderFormattedText = (text: string, keyPrefix: string) => {
    return text.split('\n').flatMap((line, lineIndex) => {
      const lineParts = line.split('**').map((part, partIndex) => (
        partIndex % 2 === 1
          ? <strong key={`${keyPrefix}-${lineIndex}-${partIndex}`}>{part}</strong>
          : <React.Fragment key={`${keyPrefix}-${lineIndex}-${partIndex}`}>{part}</React.Fragment>
      ));

      return lineIndex === 0
        ? lineParts
        : [<br key={`${keyPrefix}-${lineIndex}-br`} />, ...lineParts];
    });
  };

  const renderMessageText = (text: string, role: ChatMessage['role']) => {
    const tokens = tokenizeImageReferenceText(text, completedImageItems);
    const isUserMessage = role === 'user';

    return tokens.map((token, index) => {
      if (token.type === 'text') {
        return (
          <React.Fragment key={`text-${index}`}>
            {renderFormattedText(token.text, `text-${index}`)}
          </React.Fragment>
        );
      }

      const previewStyle = getImagePreviewFitStyle(token.item, 42, 20);

      return (
        <button
          key={`mention-${token.item.id}-${index}`}
          type="button"
          title={`定位到 ${getImageReferenceDisplayText(token.item)}`}
          onClick={() => onNavigateToImage(token.item)}
          className={`mx-1 inline-flex max-w-full translate-y-1 items-center gap-1.5 rounded-xl px-2 py-1 align-baseline text-xs font-black shadow-sm transition-all hover:scale-[1.02] active:scale-95 ${
            isUserMessage
              ? 'bg-white/15 text-white ring-1 ring-white/25 hover:bg-white/25'
              : 'bg-indigo-50 text-indigo-700 ring-1 ring-indigo-100 hover:bg-indigo-100'
          }`}
        >
          <span
            className={`shrink-0 overflow-hidden rounded-md ${
            isUserMessage ? 'bg-white/20' : 'bg-white'
          }`}
            style={previewStyle}
          >
            <img src={getThumbnailImageSrc(token.item)} className="h-full w-full object-cover" />
          </span>
          <span className="truncate">{getImageReferenceDisplayText(token.item)}</span>
        </button>
      );
    });
  };

  const renderMessageImages = (message: ChatMessage) => {
    const imageIds = message.imageIds || [];
    if (imageIds.length === 0) return null;

    const attachedImages = imageIds
      .map(imageId => imageItemsById.get(imageId))
      .filter((item): item is CanvasItem => !!item && item.type === 'image' && item.status === 'completed' && !!item.content);

    if (attachedImages.length === 0) return null;

    return (
      <div className="mt-3 grid gap-2">
        {attachedImages.map((item) => {
          const previewStyle = getImagePreviewFitStyle(item, 230, 180);
          return (
            <button
              key={item.id}
              type="button"
              onClick={() => onNavigateToImage(item)}
              title={`定位到 @${item.id}`}
              className="group overflow-hidden rounded-2xl bg-white p-2 text-left shadow-sm ring-1 ring-black/5 transition-all hover:-translate-y-0.5 hover:shadow-md hover:ring-indigo-200 active:scale-[0.99]"
            >
              <div className="overflow-hidden rounded-xl bg-gray-100" style={previewStyle}>
                <img src={getThumbnailImageSrc(item)} className="h-full w-full object-cover transition-transform group-hover:scale-[1.02]" />
              </div>
              <div className="mt-2 flex items-center justify-between gap-2 text-[11px] font-black text-indigo-700">
                <span className="truncate">@{item.id}</span>
                <span className="shrink-0 rounded-full bg-indigo-50 px-2 py-0.5 text-[10px] text-indigo-500">查看</span>
              </div>
            </button>
          );
        })}
      </div>
    );
  };

  return (
    <div className="w-96 bg-white border-l border-gray-200 h-full flex flex-col z-50 overflow-hidden">
      <div className="p-4 border-b border-gray-50 bg-gray-50/30 flex items-center gap-2">
        <div className="w-8 h-8 bg-black rounded-lg flex items-center justify-center">
          <Hammer className="text-white" size={16} />
        </div>
        <div>
          <h2 className="font-black text-gray-800 tracking-tight">livart 对话</h2>
          <p className="text-[9px] text-gray-400 uppercase tracking-widest font-black">直接生成与编辑图像</p>
        </div>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 space-y-6 scrollbar-hide">
        {messages.map((message) => (
          <div key={message.id} className={`flex flex-col ${message.role === 'user' ? 'items-end' : 'items-start'}`}>
            <div className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed ${
              message.role === 'user'
                ? 'bg-black text-white shadow-lg'
                : 'bg-gray-100 text-gray-800'
            }`}>
              {renderMessageText(message.text, message.role)}
              {renderMessageImages(message)}
            </div>
            <span className="text-[10px] text-gray-400 mt-1 px-1 font-medium">
              {new Date(message.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </span>
          </div>
        ))}

        {isThinking && (
          <div className="flex items-center gap-3 text-black text-sm font-bold animate-pulse">
            <Loader2 className="animate-spin" size={16} />
            <span>livart 正在生成中...</span>
          </div>
        )}
      </div>

      <div className="p-4 bg-white border-t border-gray-100 space-y-3">
        <form ref={formRef} onSubmit={handleSubmit} className="relative">
          <div className="mb-2 flex items-center gap-2 rounded-2xl border border-gray-100 bg-gray-50 p-1.5">
            <span className="shrink-0 px-2 text-[10px] font-black uppercase tracking-widest text-gray-400">画幅</span>
            <div className="scrollbar-hide flex min-w-0 flex-1 gap-1 overflow-x-auto">
              {IMAGE_ASPECT_RATIO_OPTIONS.map((option) => (
                <button
                  key={option.value}
                  type="button"
                  title={option.title}
                  disabled={isInputBusy}
                  onClick={() => setSelectedAspectRatio(option.value)}
                  className={`shrink-0 rounded-xl px-2.5 py-1.5 text-[11px] font-black transition-all ${
                    selectedAspectRatio === option.value
                      ? 'bg-black text-white shadow-lg shadow-black/10'
                      : 'bg-white text-gray-500 hover:bg-indigo-50 hover:text-indigo-600'
                  } disabled:opacity-40`}
                >
                  {option.label}
                </button>
              ))}
            </div>
          </div>

          <div className="flex items-end gap-2 w-full pr-2 py-2 pl-3 bg-gray-50 border border-gray-200 rounded-2xl focus-within:ring-4 focus-within:ring-black/5 focus-within:border-black transition-all">
            <ImageMentionEditor
              value={inputValue}
              imageItems={completedImageItems}
              onChange={handleInputChange}
              disabled={isInputBusy}
              placeholder={contextImage ? '输入对这张图的修改要求...' : '描述画面，或输入 @ 选择参考图...'}
              className="prompt-editor scrollbar-hide min-w-0 flex-1 min-h-[72px] max-h-28 overflow-y-auto overflow-x-hidden bg-transparent py-1.5 text-sm leading-6 font-medium outline-none whitespace-pre-wrap break-words"
              itemHint={() => '点击后插入稳定 ID 引用'}
              onSubmitShortcut={() => formRef.current?.requestSubmit()}
            />
            <button
              type="submit"
              disabled={!inputValue.trim() || isInputBusy}
              className="w-10 h-10 text-white rounded-xl hover:scale-105 active:scale-95 transition-all flex items-center justify-center shadow-lg shadow-black/10 flex-shrink-0 bg-black disabled:opacity-30"
            >
              <Send size={18} />
            </button>
          </div>
        </form>
      </div>
    </div>
  );
};

export default Sidebar;
