
import React, { useEffect, useMemo, useRef } from 'react';
import { ChatMessage, CanvasItem } from '../types';
import { Image as ImageIcon, Loader2, Hammer, Send } from 'lucide-react';
import { optimizePrompt } from '../services/promptOptimizer';

interface SidebarProps {
  messages: ChatMessage[];
  isThinking: boolean;
  onSendMessage: (text: string) => void;
  contextImage: CanvasItem | null;
  imageItems: CanvasItem[];
  onSelectContextImage: (item: CanvasItem) => void;
  onClearContextImage: () => void;
}

const Sidebar: React.FC<SidebarProps> = ({ messages, isThinking, onSendMessage, contextImage, imageItems, onSelectContextImage, onClearContextImage }) => {
  const [inputValue, setInputValue] = React.useState('');
  const [mentionQuery, setMentionQuery] = React.useState<string | null>(null);
  const [contextTagOffset, setContextTagOffset] = React.useState<number | null>(null);
  const [isOptimizingPrompt, setIsOptimizingPrompt] = React.useState(false);
  const [optimizationError, setOptimizationError] = React.useState('');
  const scrollRef = useRef<HTMLDivElement>(null);
  const inputRef = useRef<HTMLDivElement>(null);
  const lastContextImageIdRef = useRef<string | null>(null);

  const mentionImageItems = useMemo(() => {
    if (mentionQuery === null) return [];
    const normalizedQuery = mentionQuery.trim().toLowerCase();
    const availableImages = imageItems.filter(item => item.status === 'completed' && !!item.content);
    if (!normalizedQuery) return availableImages.slice(0, 6);
    return availableImages
      .filter(item => (item.label || '图片').toLowerCase().includes(normalizedQuery))
      .slice(0, 6);
  }, [imageItems, mentionQuery]);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, isThinking]);

  const readEditorState = (root: ParentNode) => {
    let text = '';
    let tagOffset: number | null = null;

    const walk = (node: Node) => {
      if (node.nodeType === Node.TEXT_NODE) {
        text += node.textContent?.replace(/\u00a0/g, ' ') || '';
        return;
      }

      if (!(node instanceof HTMLElement) && !(node instanceof DocumentFragment)) return;

      if (node instanceof HTMLElement) {
        if (node.dataset.contextTag !== undefined) {
          tagOffset = text.length;
          return;
        }
        if (node.dataset.placeholder !== undefined) {
          return;
        }
        if (node.tagName === 'BR') {
          text += '\n';
          return;
        }
      }

      node.childNodes.forEach(walk);
    };

    root.childNodes.forEach(walk);
    return { text, tagOffset };
  };

  const moveCaretToEnd = () => {
    requestAnimationFrame(() => {
      const editor = inputRef.current;
      if (!editor) return;

      editor.focus();
      const range = document.createRange();
      range.selectNodeContents(editor);
      range.collapse(false);
      const selection = window.getSelection();
      selection?.removeAllRanges();
      selection?.addRange(range);
    });
  };

  const insertPlainTextAtSelection = (text: string) => {
    document.execCommand('insertText', false, text);
  };

  const buildContextTagElement = (item: CanvasItem) => {
    const tag = document.createElement('span');
    tag.dataset.contextTag = '';
    tag.contentEditable = 'false';
    tag.tabIndex = 0;
    tag.title = '按 Backspace 或 Delete 删除引用';
    tag.className = 'mx-1 inline-flex max-w-[168px] translate-y-1 items-center gap-1.5 rounded-xl border border-indigo-100 bg-indigo-50 px-2 py-1 text-indigo-700 align-baseline outline-none focus:ring-2 focus:ring-indigo-300';

    const thumbnail = document.createElement('span');
    thumbnail.className = 'h-5 w-5 overflow-hidden rounded-md border border-indigo-100 bg-white';

    const image = document.createElement('img');
    image.src = item.content;
    image.className = 'h-full w-full object-cover';
    thumbnail.appendChild(image);

    const label = document.createElement('span');
    label.className = 'truncate text-xs font-black';
    label.textContent = `@${item.label || '参考图'}`;

    const closeButton = document.createElement('span');
    closeButton.role = 'button';
    closeButton.title = '移除引用图片';
    closeButton.className = 'rounded-md p-0.5 text-indigo-400 hover:bg-indigo-100';
    closeButton.textContent = '×';
    closeButton.addEventListener('click', (event) => {
      event.stopPropagation();
      clearContextTag();
    });

    tag.addEventListener('keydown', (event) => {
      if (event.key !== 'Backspace' && event.key !== 'Delete') return;
      event.preventDefault();
      clearContextTag();
    });

    tag.append(thumbnail, label, closeButton);
    return tag;
  };

  const syncEditorContent = (
    value: string,
    item: CanvasItem | null,
    tagOffset: number | null,
    focusEnd = false
  ) => {
    const editor = inputRef.current;
    if (!editor) return;

    editor.replaceChildren();

    if (item && tagOffset !== null) {
      const safeOffset = Math.max(0, Math.min(tagOffset, value.length));
      const beforeTag = value.slice(0, safeOffset);
      const afterTag = value.slice(safeOffset);
      if (beforeTag) editor.appendChild(document.createTextNode(beforeTag));
      editor.appendChild(buildContextTagElement(item));
      if (afterTag) editor.appendChild(document.createTextNode(afterTag));
    } else if (value) {
      editor.appendChild(document.createTextNode(value));
    }

    if (focusEnd) moveCaretToEnd();
  };

  const clearContextTag = () => {
    setContextTagOffset(null);
    lastContextImageIdRef.current = null;
    onClearContextImage();
    syncEditorContent(inputValue, null, null, true);
  };

  useEffect(() => {
    const currentContextImageId = contextImage?.id ?? null;
    if (currentContextImageId === lastContextImageIdRef.current) return;

    lastContextImageIdRef.current = currentContextImageId;

    if (!contextImage) {
      setContextTagOffset(null);
      syncEditorContent(inputValue, null, null);
      return;
    }

    const nextTagOffset = inputValue.length;
    setContextTagOffset(nextTagOffset);
    requestAnimationFrame(() => syncEditorContent(inputValue, contextImage, nextTagOffset, true));
  }, [contextImage?.id]);

  const handleSubmit = async (e: React.FormEvent) => {
    e.preventDefault();
    const prompt = inputValue.trim();
    if (prompt && !isThinking && !isOptimizingPrompt) {
      setIsOptimizingPrompt(true);
      setOptimizationError('');

      try {
        const optimizedPrompt = await optimizePrompt(prompt, contextImage ? 'image-to-image' : 'text-to-image');
        onSendMessage(optimizedPrompt);
        setInputValue('');
        setMentionQuery(null);
        setContextTagOffset(null);
        setOptimizationError('');
        syncEditorContent('', null, null);
      } catch (error) {
        const message = error instanceof Error ? error.message : '提示词优化失败';
        setOptimizationError(message);
      } finally {
        setIsOptimizingPrompt(false);
      }
    }
  };

  const updateMentionQuery = (value: string) => {
    const match = value.match(/@([^\s@]*)$/);
    setMentionQuery(match ? match[1] : null);
  };

  const handleInputChange = (event: React.FormEvent<HTMLDivElement>) => {
    const nextState = readEditorState(event.currentTarget);
    setInputValue(nextState.text);
    setContextTagOffset(nextState.tagOffset);
    updateMentionQuery(nextState.text);

    if (contextImage && nextState.tagOffset === null) {
      onClearContextImage();
    }
  };

  const handleMentionSelect = (item: CanvasItem) => {
    const match = inputValue.match(/@([^\s@]*)$/);
    const nextValue = inputValue.replace(/@([^\s@]*)$/, '');
    const nextTagOffset = match?.index ?? nextValue.length;

    lastContextImageIdRef.current = item.id;
    onSelectContextImage(item);
    setInputValue(nextValue);
    setContextTagOffset(nextTagOffset);
    setMentionQuery(null);
    syncEditorContent(nextValue, item, nextTagOffset, true);
  };

  const handleInputKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    event.stopPropagation();

    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      event.currentTarget.closest('form')?.requestSubmit();
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      insertPlainTextAtSelection('\n');
      return;
    }

    if (!contextImage) return;

    const editor = event.currentTarget;
    const selection = window.getSelection();
    const range = selection?.rangeCount ? selection.getRangeAt(0) : null;
    if (!range || !editor.contains(range.startContainer)) return;

    const beforeRange = document.createRange();
    beforeRange.selectNodeContents(editor);
    beforeRange.setEnd(range.startContainer, range.startOffset);
    const textBeforeCaret = readEditorState(beforeRange.cloneContents()).text;
    const shouldDeleteTag = event.key === 'Backspace' && textBeforeCaret.length === (contextTagOffset ?? 0);

    if (shouldDeleteTag) {
      event.preventDefault();
      clearContextTag();
    }
  };

  const handleInputPaste = (event: React.ClipboardEvent<HTMLDivElement>) => {
    event.preventDefault();
    insertPlainTextAtSelection(event.clipboardData.getData('text/plain'));
  };

  const renderText = (text: string) => {
    return text.split('\n').map((line, i) => (
      <span key={i}>
        {line.split('**').map((part, j) => j % 2 === 1 ? <strong key={j}>{part}</strong> : part)}
        <br />
      </span>
    ));
  };

  return (
    <div className="w-96 bg-white border-l border-gray-100 h-full flex flex-col shadow-2xl z-50 overflow-hidden">
      <div className="p-4 border-b border-gray-50 bg-gray-50/30 flex items-center gap-2">
        <div className="w-8 h-8 bg-black rounded-lg flex items-center justify-center">
          <Hammer className="text-white" size={16} />
        </div>
        <div>
          <h2 className="font-black text-gray-800 tracking-tight">灵匠 对话</h2>
          <p className="text-[9px] text-gray-400 uppercase tracking-widest font-black">直接生成与编辑图像</p>
        </div>
      </div>

      <div ref={scrollRef} className="flex-1 overflow-y-auto p-4 space-y-6 scrollbar-hide">
        {messages.map((msg) => (
          <div key={msg.id} className={`flex flex-col ${msg.role === 'user' ? 'items-end' : 'items-start'}`}>
            <div className={`max-w-[85%] rounded-2xl px-4 py-3 text-sm leading-relaxed ${
              msg.role === 'user' 
                ? 'bg-black text-white shadow-lg' 
                : 'bg-gray-100 text-gray-800'
            }`}>
              {renderText(msg.text)}
            </div>
            <span className="text-[10px] text-gray-400 mt-1 px-1 font-medium">
              {new Date(msg.timestamp).toLocaleTimeString([], { hour: '2-digit', minute: '2-digit' })}
            </span>
          </div>
        ))}

        {isThinking && (
          <div className="flex items-center gap-3 text-black text-sm font-bold animate-pulse">
            <Loader2 className="animate-spin" size={16} />
            <span>灵匠正在生成中...</span>
          </div>
        )}
      </div>

      <div className="p-4 bg-white border-t border-gray-100 space-y-3">
        <form onSubmit={handleSubmit} className="relative">
          {mentionQuery !== null && (
            <div className="absolute bottom-full left-0 right-0 mb-2 p-2 bg-white border border-gray-100 rounded-2xl shadow-2xl z-20 space-y-1">
              <div className="flex items-center gap-2 px-2 py-1 text-[10px] font-black uppercase tracking-widest text-gray-400">
                <ImageIcon size={12} />
                选择画布图片
              </div>
              {mentionImageItems.length > 0 ? (
                mentionImageItems.map((item) => (
                  <button
                    key={item.id}
                    type="button"
                    onMouseDown={(event) => event.preventDefault()}
                    onClick={() => handleMentionSelect(item)}
                    className="w-full flex items-center gap-3 p-2 rounded-xl hover:bg-indigo-50 text-left transition-all"
                  >
                    <div className="w-10 h-10 rounded-lg overflow-hidden bg-gray-100 border border-gray-100 flex-shrink-0">
                      <img src={item.content} className="w-full h-full object-cover" />
                    </div>
                    <div className="min-w-0">
                      <p className="text-xs font-black text-gray-800 truncate">{item.label || '未命名图片'}</p>
                      <p className="text-[10px] font-bold text-gray-400">点击后锁定为参考图</p>
                    </div>
                  </button>
                ))
              ) : (
                <div className="px-3 py-4 text-xs font-bold text-gray-400 text-center">
                  没有匹配的画布图片
                </div>
              )}
            </div>
          )}

          <div className="flex items-end gap-2 w-full pr-2 py-2 pl-3 bg-gray-50 border border-gray-200 rounded-2xl focus-within:ring-4 focus-within:ring-black/5 focus-within:border-black transition-all">
            <div
              ref={inputRef}
              role="textbox"
              aria-multiline="true"
              data-placeholder={contextImage ? "输入对这张图的修改要求..." : "描述画面，或输入 @ 选择参考图..."}
              contentEditable
              suppressContentEditableWarning
              onInput={handleInputChange}
              onKeyDown={handleInputKeyDown}
              onFocus={() => updateMentionQuery(inputValue)}
              onPaste={handleInputPaste}
              className="prompt-editor scrollbar-hide min-w-0 flex-1 min-h-[72px] max-h-28 overflow-y-auto overflow-x-hidden bg-transparent py-1.5 text-sm leading-6 font-medium outline-none whitespace-pre-wrap break-words"
            />
            <button
              type="submit"
              disabled={!inputValue.trim() || isThinking || isOptimizingPrompt}
              className="w-10 h-10 bg-black text-white rounded-xl disabled:opacity-30 hover:scale-105 active:scale-95 transition-all flex items-center justify-center shadow-lg shadow-black/10 flex-shrink-0"
            >
              <Send size={18} />
            </button>
          </div>
          {optimizationError && (
            <div className="mt-2 rounded-xl bg-red-50 border border-red-100 px-3 py-2 text-[11px] font-bold text-red-500">
              提示词优化失败：{optimizationError}
            </div>
          )}
        </form>
      </div>
    </div>
  );
};

export default Sidebar;
