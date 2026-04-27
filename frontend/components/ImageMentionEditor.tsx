import React, { useEffect, useMemo, useRef, useState } from 'react';
import { createPortal } from 'react-dom';
import { Image as ImageIcon, Search, X } from 'lucide-react';
import type { CanvasItem } from '../types';
import { getImagePreviewFitStyle, getThumbnailImageSrc, hasUsableImageSource } from '../services/imageSources';
import {
  getImageReferenceDisplayText,
  getImageReferenceLabel,
  getImageReferenceMentionLabel,
  getImageReferenceMentionValue,
  getTrailingImageMentionQuery,
  insertImageMention,
  tokenizeImageReferenceText
} from '../services/imageReferences';

interface ImageMentionEditorProps {
  value: string;
  imageItems: CanvasItem[];
  selectableImageItems?: CanvasItem[];
  onChange: (value: string) => void;
  disabled?: boolean;
  placeholder: string;
  className: string;
  dropdownTitle?: string;
  dropdownClassName?: string;
  emptyText?: string;
  itemHint?: (item: CanvasItem) => string;
  onSubmitShortcut?: () => void;
}

const readEditorText = (root: ParentNode) => {
  let text = '';

  const walk = (node: Node) => {
    if (node.nodeType === Node.TEXT_NODE) {
      text += node.textContent?.replace(/\u00a0/g, ' ') || '';
      return;
    }

    if (!(node instanceof HTMLElement) && !(node instanceof DocumentFragment)) return;

    if (node instanceof HTMLElement) {
      if (node.dataset.imageMentionValue) {
        text += node.dataset.imageMentionValue;
        return;
      }
      if (node.dataset.placeholder !== undefined) return;
      if (node.tagName === 'BR') {
        text += '\n';
        return;
      }
    }

    node.childNodes.forEach(walk);
  };

  root.childNodes.forEach(walk);
  return text;
};

const moveCaretToEnd = (editor: HTMLElement) => {
  requestAnimationFrame(() => {
    editor.focus();
    const range = document.createRange();
    range.selectNodeContents(editor);
    range.collapse(false);
    const selection = window.getSelection();
    selection?.removeAllRanges();
    selection?.addRange(range);
  });
};

const getAdjacentMentionElement = (editor: HTMLElement, key: string) => {
  const selection = window.getSelection();
  const range = selection?.rangeCount ? selection.getRangeAt(0) : null;
  if (!range || !range.collapsed || !editor.contains(range.startContainer)) return null;

  const isBackspace = key === 'Backspace';
  const container = range.startContainer;

  if (container === editor) {
    const childIndex = isBackspace ? range.startOffset - 1 : range.startOffset;
    const candidate = editor.childNodes[childIndex];
    return candidate instanceof HTMLElement && candidate.dataset.imageMentionValue ? candidate : null;
  }

  if (container.nodeType !== Node.TEXT_NODE) return null;

  const textContent = container.textContent || '';
  const sibling = isBackspace && range.startOffset === 0
    ? container.previousSibling
    : !isBackspace && range.startOffset === textContent.length
      ? container.nextSibling
      : null;

  return sibling instanceof HTMLElement && sibling.dataset.imageMentionValue ? sibling : null;
};

const ImageMentionEditor: React.FC<ImageMentionEditorProps> = ({
  value,
  imageItems,
  selectableImageItems,
  onChange,
  disabled = false,
  placeholder,
  className,
  dropdownTitle = '选择画布图片',
  dropdownClassName = 'rounded-[28px] border border-gray-100 bg-white p-4 text-gray-900 shadow-[0_32px_120px_-24px_rgba(15,23,42,0.28)] ring-1 ring-black/[0.02]',
  emptyText = '没有匹配的画布图片',
  itemHint,
  onSubmitShortcut
}) => {
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const [searchQuery, setSearchQuery] = useState('');
  const [activePreviewId, setActivePreviewId] = useState<string | null>(null);
  const [dropdownStyle, setDropdownStyle] = useState<React.CSSProperties>({});
  const wrapperRef = useRef<HTMLDivElement>(null);
  const dropdownRef = useRef<HTMLDivElement>(null);
  const editorRef = useRef<HTMLDivElement>(null);
  const mentionListRef = useRef<HTMLDivElement>(null);
  const mentionItemRefs = useRef<Record<string, HTMLButtonElement | null>>({});
  const lastEditorValueRef = useRef(value);

  const mentionImageItems = useMemo(() => {
    if (mentionQuery === null) return [];

    const normalizedQuery = searchQuery.trim().toLowerCase();
    const availableImages = (selectableImageItems || imageItems)
      .filter(item => item.status === 'completed' && hasUsableImageSource(item))
      .slice()
      .reverse();

    if (!normalizedQuery) return availableImages.slice(0, 12);

    return availableImages
      .filter(item => {
        const label = getImageReferenceLabel(item).toLowerCase();
        const mentionLabel = getImageReferenceMentionLabel(item).toLowerCase();
        const stableId = item.id.toLowerCase();
        return label.includes(normalizedQuery) || mentionLabel.includes(normalizedQuery) || stableId.includes(normalizedQuery);
      })
      .slice(0, 12);
  }, [imageItems, mentionQuery, searchQuery, selectableImageItems]);

  const activePreviewItem = useMemo(() => {
    return mentionImageItems.find(item => item.id === activePreviewId) || mentionImageItems[0] || null;
  }, [activePreviewId, mentionImageItems]);

  useEffect(() => {
    if (mentionQuery === null) {
      setActivePreviewId(null);
      setSearchQuery('');
      return;
    }

    if (!mentionImageItems.length) {
      setActivePreviewId(null);
      return;
    }

    setActivePreviewId(currentId =>
      currentId && mentionImageItems.some(item => item.id === currentId)
        ? currentId
        : mentionImageItems[0].id
    );
  }, [mentionImageItems, mentionQuery]);

  useEffect(() => {
    if (mentionQuery === null || !activePreviewId) return;

    const list = mentionListRef.current;
    const activeItem = mentionItemRefs.current[activePreviewId];
    if (!list || !activeItem) return;

    const listRect = list.getBoundingClientRect();
    const itemRect = activeItem.getBoundingClientRect();
    const padding = 8;

    if (itemRect.top < listRect.top + padding) {
      list.scrollTop -= (listRect.top + padding) - itemRect.top;
      return;
    }

    if (itemRect.bottom > listRect.bottom - padding) {
      list.scrollTop += itemRect.bottom - (listRect.bottom - padding);
    }
  }, [activePreviewId, mentionQuery]);

  const closeMentionPicker = () => {
    setMentionQuery(null);
    setSearchQuery('');
    setActivePreviewId(null);
  };

  useEffect(() => {
    if (mentionQuery === null || disabled) return;

    const updateDropdownPosition = () => {
      const wrapper = wrapperRef.current;
      if (!wrapper) return;

      const rect = wrapper.getBoundingClientRect();
      const panelWidth = Math.min(760, window.innerWidth - 24);
      const estimatedPanelHeight = 450;
      const preferredLeft = rect.left + rect.width / 2 - panelWidth / 2;
      const left = Math.max(12, Math.min(preferredLeft, window.innerWidth - panelWidth - 12));
      const topAbove = rect.top - estimatedPanelHeight - 12;
      const topBelow = rect.bottom + 12;
      const top = topAbove >= 12
        ? topAbove
        : Math.min(topBelow, Math.max(12, window.innerHeight - estimatedPanelHeight - 12));

      setDropdownStyle({
        position: 'fixed',
        left,
        top,
        width: panelWidth,
        maxHeight: Math.min(estimatedPanelHeight, window.innerHeight - top - 12),
        zIndex: 4000000
      });
    };

    updateDropdownPosition();
    window.addEventListener('resize', updateDropdownPosition);
    window.addEventListener('scroll', updateDropdownPosition, true);

    return () => {
      window.removeEventListener('resize', updateDropdownPosition);
      window.removeEventListener('scroll', updateDropdownPosition, true);
    };
  }, [disabled, mentionQuery, mentionImageItems.length]);

  useEffect(() => {
    if (mentionQuery === null || disabled) return;

    const handleDocumentMouseDown = (event: MouseEvent) => {
      const target = event.target;
      if (!(target instanceof Node)) return;
      if (wrapperRef.current?.contains(target)) return;
      if (dropdownRef.current?.contains(target)) return;
      closeMentionPicker();
    };

    document.addEventListener('mousedown', handleDocumentMouseDown);

    return () => {
      document.removeEventListener('mousedown', handleDocumentMouseDown);
    };
  }, [disabled, mentionQuery]);

  const openMentionPicker = (query: string | null) => {
    setMentionQuery(query);
    setSearchQuery(query || '');
  };

  const syncEditorContent = (nextValue: string, focusEnd = false) => {
    const editor = editorRef.current;
    if (!editor) return;

    editor.replaceChildren();

    for (const token of tokenizeImageReferenceText(nextValue, imageItems)) {
      if (token.type === 'text') {
        if (token.text) editor.appendChild(document.createTextNode(token.text));
        continue;
      }

      const chip = document.createElement('span');
      chip.dataset.imageMentionValue = getImageReferenceMentionValue(token.item);
      chip.contentEditable = 'false';
      chip.className = 'mx-1 inline-flex max-w-[168px] translate-y-1 select-none items-center gap-1.5 rounded-xl border border-indigo-100 bg-indigo-50 px-2 py-1 text-indigo-700 align-baseline shadow-sm';
      chip.title = `${getImageReferenceMentionValue(token.item)}，按 Backspace 或 Delete 整体删除`;

      const thumbnail = document.createElement('span');
      thumbnail.className = 'shrink-0 overflow-hidden rounded-md border border-indigo-100 bg-white';
      const chipPreviewStyle = getImagePreviewFitStyle(token.item, 42, 20);
      thumbnail.style.width = chipPreviewStyle.width;
      thumbnail.style.height = chipPreviewStyle.height;

      const image = document.createElement('img');
      image.src = getThumbnailImageSrc(token.item);
      image.className = 'h-full w-full object-cover';
      thumbnail.appendChild(image);

      const label = document.createElement('span');
      label.className = 'truncate text-xs font-black';
      label.textContent = getImageReferenceDisplayText(token.item);

      chip.append(thumbnail, label);
      editor.appendChild(chip);
    }

    if (focusEnd) moveCaretToEnd(editor);
  };

  const emitEditorValue = (nextValue: string) => {
    lastEditorValueRef.current = nextValue;
    onChange(nextValue);
    openMentionPicker(getTrailingImageMentionQuery(nextValue));
  };

  useEffect(() => {
    if (value === lastEditorValueRef.current) return;
    lastEditorValueRef.current = value;
    syncEditorContent(value);
    closeMentionPicker();
  }, [value, imageItems]);

  useEffect(() => {
    syncEditorContent(value);
  }, []);

  const handleInput = (event: React.FormEvent<HTMLDivElement>) => {
    emitEditorValue(readEditorText(event.currentTarget));
  };

  const handlePaste = (event: React.ClipboardEvent<HTMLDivElement>) => {
    event.preventDefault();
    document.execCommand('insertText', false, event.clipboardData.getData('text/plain'));
  };

  const moveActivePreview = (step: number) => {
    if (!mentionImageItems.length) return;

    const currentIndex = activePreviewItem
      ? mentionImageItems.findIndex(item => item.id === activePreviewItem.id)
      : -1;
    const nextIndex = currentIndex >= 0
      ? (currentIndex + step + mentionImageItems.length) % mentionImageItems.length
      : step > 0 ? 0 : mentionImageItems.length - 1;

    setActivePreviewId(mentionImageItems[nextIndex].id);
  };

  const handlePickerNavigationKeyDown = (event: React.KeyboardEvent<HTMLElement>) => {
    if (mentionQuery === null) return false;

    if (event.key === 'ArrowDown' || event.key === 'ArrowUp') {
      event.preventDefault();
      event.stopPropagation();
      moveActivePreview(event.key === 'ArrowDown' ? 1 : -1);
      return true;
    }

    if (event.key === 'Enter' && activePreviewItem && !event.ctrlKey && !event.metaKey && !event.shiftKey && !event.altKey) {
      event.preventDefault();
      event.stopPropagation();
      handleMentionSelect(activePreviewItem);
      return true;
    }

    return false;
  };

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    event.stopPropagation();

    if (event.key === 'Escape') {
      closeMentionPicker();
      return;
    }

    if (handlePickerNavigationKeyDown(event)) return;

    if (event.key === 'Enter' && (event.ctrlKey || event.metaKey)) {
      event.preventDefault();
      onSubmitShortcut?.();
      return;
    }

    if (event.key === 'Enter') {
      event.preventDefault();
      document.execCommand('insertText', false, '\n');
      return;
    }

    if (event.key !== 'Backspace' && event.key !== 'Delete') return;

    const editor = editorRef.current;
    if (!editor) return;
    const mentionElement = getAdjacentMentionElement(editor, event.key);
    if (!mentionElement) return;

    event.preventDefault();
    mentionElement.remove();
    emitEditorValue(readEditorText(editor));
  };

  const handleMentionSelect = (item: CanvasItem) => {
    const editor = editorRef.current;
    const currentValue = editor ? readEditorText(editor) : value;
    const nextValue = insertImageMention(currentValue, item, imageItems);
    lastEditorValueRef.current = nextValue;
    onChange(nextValue);
    closeMentionPicker();
    syncEditorContent(nextValue, true);
  };

  const dropdown = mentionQuery !== null && !disabled ? (
    <div ref={dropdownRef} className={`${dropdownClassName} flex flex-col overflow-hidden`} style={dropdownStyle} role="dialog" aria-label={dropdownTitle}>
      <div className="mb-3 flex shrink-0 items-start justify-between gap-3">
        <div className="min-w-0">
          <div className="flex items-center gap-2">
            <span className="flex h-9 w-9 items-center justify-center rounded-2xl bg-indigo-50 text-indigo-600 ring-1 ring-indigo-100">
              <ImageIcon size={16} />
            </span>
            <div className="min-w-0">
              <p className="truncate text-sm font-black text-gray-900">{dropdownTitle}</p>
              <p className="truncate text-[11px] font-bold text-gray-400">选择后会插入一个可点击的图片引用</p>
            </div>
          </div>
        </div>
        <button
          type="button"
          title="关闭选择框"
          onMouseDown={(event) => event.preventDefault()}
          onClick={closeMentionPicker}
          className="flex h-9 w-9 shrink-0 items-center justify-center rounded-2xl border border-gray-100 bg-white text-gray-400 shadow-sm transition-all hover:border-gray-200 hover:bg-gray-50 hover:text-gray-700 active:scale-95"
        >
          <X size={16} />
        </button>
      </div>

      <div className="mb-4 flex h-11 shrink-0 items-center gap-2 rounded-2xl border border-gray-100 bg-gray-50 px-3 text-gray-500 focus-within:border-indigo-200 focus-within:bg-white focus-within:ring-4 focus-within:ring-indigo-50">
        <Search size={15} className="shrink-0 text-gray-400" />
        <input
          value={searchQuery}
          onChange={(event) => setSearchQuery(event.target.value)}
          onMouseDown={(event) => event.stopPropagation()}
          onKeyDown={(event) => {
            event.stopPropagation();
            if (event.key === 'Escape') closeMentionPicker();
            if (handlePickerNavigationKeyDown(event)) return;
          }}
          aria-label="搜索画布图片"
          placeholder="按图片名字或 ID 搜索"
          className="h-full min-w-0 flex-1 bg-transparent text-sm font-semibold text-gray-800 outline-none placeholder:text-gray-400"
        />
        {searchQuery && (
          <button
            type="button"
            title="清空搜索"
            onMouseDown={(event) => event.preventDefault()}
            onClick={() => setSearchQuery('')}
            className="rounded-xl px-2 py-1 text-xs font-black text-gray-400 transition-colors hover:bg-white hover:text-gray-700"
          >
            清空
          </button>
        )}
      </div>

      <div className="grid min-h-0 flex-1 grid-cols-[260px_minmax(0,1fr)] gap-4 overflow-hidden">
        <div ref={mentionListRef} className="min-h-0 min-w-0 space-y-1 overflow-y-auto overflow-x-hidden pr-1 scrollbar-thin scrollbar-thumb-gray-200 scrollbar-track-transparent">
          {mentionImageItems.length > 0 ? (
            mentionImageItems.map((item) => {
              const listPreviewStyle = getImagePreviewFitStyle(item, 46, 42);
              const isActive = activePreviewItem?.id === item.id;

              return (
                <button
                  key={item.id}
                  ref={(element) => {
                    mentionItemRefs.current[item.id] = element;
                  }}
                  type="button"
                  title={itemHint?.(item) || `点击后插入稳定引用 ${getImageReferenceMentionLabel(item)}`}
                  aria-selected={isActive}
                  onMouseDown={(event) => event.preventDefault()}
                  onMouseEnter={() => setActivePreviewId(item.id)}
                  onFocus={() => setActivePreviewId(item.id)}
                  onClick={() => handleMentionSelect(item)}
                  className={`flex h-14 w-full min-w-0 items-center gap-3 rounded-2xl px-2 text-left transition-all active:scale-[0.99] ${
                    isActive ? 'bg-indigo-50 ring-1 ring-indigo-100' : 'hover:bg-gray-50'
                  }`}
                >
                  <span className="flex h-11 w-12 shrink-0 items-center justify-center rounded-xl bg-gray-100">
                    <span className="overflow-hidden rounded-lg bg-white shadow-sm ring-1 ring-gray-100" style={listPreviewStyle}>
                      <img src={getThumbnailImageSrc(item)} className="h-full w-full object-contain" />
                    </span>
                  </span>
                  <span className="min-w-0 flex-1">
                    <span className="block truncate text-sm font-black text-gray-800">
                      {getImageReferenceLabel(item)}
                    </span>
                    <span className="block truncate text-[10px] font-bold text-gray-400">
                      @{getImageReferenceMentionLabel(item)}
                    </span>
                  </span>
                </button>
              );
            })
          ) : (
            <div className="flex h-full flex-col items-center justify-center rounded-2xl border border-dashed border-gray-200 bg-gray-50 px-4 text-center">
              <div className="mb-2 flex h-10 w-10 items-center justify-center rounded-2xl bg-white text-gray-300 shadow-sm">
                <Search size={16} />
              </div>
              <p className="text-sm font-black text-gray-500">{emptyText}</p>
              <p className="mt-1 text-xs font-medium text-gray-400">换个图片名字或 ID 试试</p>
            </div>
          )}
        </div>

        <div className="flex min-h-0 min-w-0 flex-col">
          <div className="flex min-h-0 flex-1 items-center justify-center overflow-hidden rounded-[24px] border border-gray-100 bg-[radial-gradient(circle_at_top,#eef2ff,transparent_34%),#fff] shadow-xl shadow-indigo-950/10">
            {activePreviewItem ? (
              <img
                src={getThumbnailImageSrc(activePreviewItem)}
                className="max-h-full max-w-full object-contain"
                style={getImagePreviewFitStyle(activePreviewItem, 430, 292)}
              />
            ) : (
              <div className="text-sm font-bold text-gray-400">暂无可选图片</div>
            )}
          </div>
          <div className="mt-3 flex items-center justify-between gap-3">
            <div className="min-w-0">
              <p className="truncate text-sm font-black text-gray-900">
                {activePreviewItem ? getImageReferenceLabel(activePreviewItem) : '未选择资源'}
              </p>
              <p className="truncate text-[11px] font-bold text-gray-400">
                {activePreviewItem ? `@${getImageReferenceMentionLabel(activePreviewItem)}` : '输入 @ 后选择一张画布图片'}
              </p>
            </div>
            <div className="shrink-0 rounded-xl bg-indigo-50 px-3 py-2 text-xs font-black text-indigo-600">
              ↑↓ 选择 · Enter 插入
            </div>
          </div>
        </div>
      </div>
    </div>
  ) : null;

  return (
    <div ref={wrapperRef} className="relative min-w-0 flex-1">
      {dropdown && createPortal(dropdown, document.body)}
      <div
        ref={editorRef}
        role="textbox"
        aria-multiline="true"
        aria-disabled={disabled}
        data-placeholder={placeholder}
        contentEditable={!disabled}
        suppressContentEditableWarning
        onInput={handleInput}
        onKeyDown={handleKeyDown}
        onPaste={handlePaste}
        className={className}
      />
    </div>
  );
};

export default ImageMentionEditor;
