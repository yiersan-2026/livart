
import React, { useEffect, useRef } from 'react';
import { ChatMessage, CanvasItem } from '../types';
import { Loader2, Hammer, Send, X } from 'lucide-react';

interface SidebarProps {
  messages: ChatMessage[];
  isThinking: boolean;
  onSendMessage: (text: string) => void;
  contextImage: CanvasItem | null;
  onClearContextImage: () => void;
}

const Sidebar: React.FC<SidebarProps> = ({ messages, isThinking, onSendMessage, contextImage, onClearContextImage }) => {
  const [inputValue, setInputValue] = React.useState('');
  const scrollRef = useRef<HTMLDivElement>(null);

  useEffect(() => {
    if (scrollRef.current) {
      scrollRef.current.scrollTop = scrollRef.current.scrollHeight;
    }
  }, [messages, isThinking]);

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (inputValue.trim()) {
      onSendMessage(inputValue);
      setInputValue('');
    }
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
        {contextImage && (
          <div className="flex items-center gap-3 p-2 bg-indigo-50 rounded-xl border border-indigo-100 animate-in slide-in-from-bottom-2">
            <div className="w-12 h-12 rounded-lg overflow-hidden bg-white border border-indigo-200 flex-shrink-0">
              <img src={contextImage.content} className="w-full h-full object-cover" />
            </div>
            <div className="flex-1 min-w-0">
              <p className="text-[10px] font-black uppercase tracking-widest text-indigo-400">灵感参考</p>
              <p className="text-xs font-bold text-indigo-900 truncate">{contextImage.label || '参考图片'}</p>
            </div>
            <button 
              onClick={onClearContextImage}
              className="p-1.5 hover:bg-indigo-100 rounded-lg text-indigo-400 transition-colors"
            >
              <X size={16} />
            </button>
          </div>
        )}
        
        <form onSubmit={handleSubmit} className="relative">
          <input
            type="text"
            value={inputValue}
            onChange={(e) => setInputValue(e.target.value)}
            placeholder={contextImage ? "输入对参考图的修改要求..." : "描述你想要生成的画面..."}
            className="w-full pl-4 pr-12 py-3.5 bg-gray-50 border border-gray-200 rounded-2xl text-sm font-medium focus:outline-none focus:ring-4 focus:ring-black/5 focus:border-black transition-all"
          />
          <button
            type="submit"
            disabled={!inputValue.trim() || isThinking}
            className="absolute right-2 top-1/2 -translate-y-1/2 w-10 h-10 bg-black text-white rounded-xl disabled:opacity-30 hover:scale-105 active:scale-95 transition-all flex items-center justify-center shadow-lg shadow-black/10"
          >
            <Send size={18} />
          </button>
        </form>
      </div>
    </div>
  );
};

export default Sidebar;
