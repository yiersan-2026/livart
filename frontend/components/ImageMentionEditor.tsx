import React, { useEffect, useMemo, useRef, useState } from 'react';
import { Image as ImageIcon } from 'lucide-react';
import type { CanvasItem } from '../types';
import { getThumbnailImageSrc } from '../services/imageSources';
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
  dropdownClassName = 'absolute bottom-full left-0 right-0 mb-2 rounded-2xl border border-gray-100 bg-white p-2 shadow-2xl z-30 space-y-1',
  emptyText = '没有匹配的画布图片',
  itemHint,
  onSubmitShortcut
}) => {
  const [mentionQuery, setMentionQuery] = useState<string | null>(null);
  const editorRef = useRef<HTMLDivElement>(null);
  const lastEditorValueRef = useRef(value);

  const mentionImageItems = useMemo(() => {
    if (mentionQuery === null) return [];

    const normalizedQuery = mentionQuery.trim().toLowerCase();
    const availableImages = (selectableImageItems || imageItems)
      .filter(item => item.status === 'completed' && !!item.content);

    if (!normalizedQuery) return availableImages.slice(0, 6);

    return availableImages
      .filter(item => {
        const label = getImageReferenceLabel(item).toLowerCase();
        const mentionLabel = getImageReferenceMentionLabel(item).toLowerCase();
        return label.includes(normalizedQuery) || mentionLabel.includes(normalizedQuery);
      })
      .slice(0, 6);
  }, [imageItems, mentionQuery, selectableImageItems]);

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
      thumbnail.className = 'h-5 w-5 overflow-hidden rounded-md border border-indigo-100 bg-white';

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
    setMentionQuery(getTrailingImageMentionQuery(nextValue));
  };

  useEffect(() => {
    if (value === lastEditorValueRef.current) return;
    lastEditorValueRef.current = value;
    syncEditorContent(value);
    setMentionQuery(getTrailingImageMentionQuery(value));
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

  const handleKeyDown = (event: React.KeyboardEvent<HTMLDivElement>) => {
    event.stopPropagation();

    if (event.key === 'Escape') {
      setMentionQuery(null);
      return;
    }

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
    setMentionQuery(null);
    syncEditorContent(nextValue, true);
  };

  return (
    <div className="relative min-w-0 flex-1">
      {mentionQuery !== null && !disabled && (
        <div className={dropdownClassName}>
          <div className="flex items-center gap-2 px-2 py-1 text-[10px] font-black uppercase tracking-widest text-gray-400">
            <ImageIcon size={12} />
            {dropdownTitle}
          </div>
          {mentionImageItems.length > 0 ? (
            <div className="grid max-h-72 grid-cols-3 gap-2 overflow-y-auto px-1 pb-1">
              {mentionImageItems.map((item) => (
                <button
                  key={item.id}
                  type="button"
                  title={itemHint?.(item) || `点击后插入稳定引用 ${getImageReferenceMentionLabel(item)}`}
                  onMouseDown={(event) => event.preventDefault()}
                  onClick={() => handleMentionSelect(item)}
                  className="flex min-w-0 flex-col items-center gap-1.5 rounded-xl p-2 text-center transition-all hover:bg-indigo-50 active:scale-95"
                >
                  <div className="h-20 w-20 overflow-hidden rounded-2xl border border-gray-100 bg-gray-100 shadow-sm">
                    <img src={getThumbnailImageSrc(item)} className="h-full w-full object-cover" />
                  </div>
                  <p className="w-full truncate text-[11px] font-black text-gray-800">{getImageReferenceLabel(item)}</p>
                </button>
              ))}
            </div>
          ) : (
            <div className="px-3 py-4 text-xs font-bold text-gray-400 text-center">
              {emptyText}
            </div>
          )}
        </div>
      )}
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
        onFocus={() => setMentionQuery(getTrailingImageMentionQuery(value))}
        onPaste={handlePaste}
        className={className}
      />
    </div>
  );
};

export default ImageMentionEditor;
