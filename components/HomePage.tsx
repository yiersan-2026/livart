
import React, { useState } from 'react';
import { Sparkles, Plus, ArrowUp, Hammer, Bot, Image as ImageIcon, Video, FolderPlus } from 'lucide-react';

interface HomePageProps {
  onStart: (prompt: string) => void;
  onEnterCanvas: () => void;
}

type CreationMode = 'chat' | 'image' | 'video';

const HomePage: React.FC<HomePageProps> = ({ onStart, onEnterCanvas }) => {
  const [input, setInput] = useState('');
  const [activeMode, setActiveMode] = useState<CreationMode>('image');

  const handleSubmit = (e: React.FormEvent) => {
    e.preventDefault();
    if (input.trim()) {
      onStart(input);
    }
  };

  return (
    <div className="min-h-screen flex flex-col items-center relative overflow-hidden">
      {/* 顶部 Logo 区域 - 风格与 App 头部统一 */}
      <header className="w-full p-8 flex items-center justify-between z-10">
        <div className="flex items-center gap-4">
          <div className="w-11 h-11 bg-black rounded-xl flex items-center justify-center shadow-lg hover:scale-105 transition-transform cursor-pointer">
            <Hammer className="text-white" size={20} />
          </div>
          <div className="flex flex-col -gap-1">
            <h1 className="text-2xl font-black tracking-tighter text-gray-900 leading-none">灵匠</h1>
            <span className="text-[9px] font-black uppercase tracking-[0.2em] text-gray-400">Artisan AI Lab</span>
          </div>
        </div>
        
        <div className="flex items-center gap-4">
          <button 
            onClick={onEnterCanvas}
            className="flex items-center gap-2 px-5 py-2.5 bg-white border border-gray-100 text-gray-900 rounded-xl font-bold text-sm hover:bg-gray-50 active:scale-95 transition-all shadow-sm"
          >
            <FolderPlus size={18} />
            进入画布
          </button>
          <button className="px-5 py-2.5 bg-black text-white rounded-xl font-bold text-sm hover:opacity-90 active:scale-95 transition-all shadow-lg">
            交付作品
          </button>
        </div>
      </header>

      {/* 主体内容 - 极简主义 */}
      <main className="flex-1 flex flex-col items-center justify-center -mt-32 px-6 w-full max-w-4xl z-10">
        <div className="text-center mb-12 space-y-4">
          <div className="inline-flex items-center gap-2 px-3 py-1 bg-white/80 border border-gray-100 rounded-full shadow-sm">
            <Sparkles className="text-indigo-500" size={14} />
            <span className="text-[10px] font-black uppercase tracking-widest text-gray-500">AI 时代的创意重塑</span>
          </div>
          <h2 className="text-6xl font-black text-gray-900 tracking-tight">
            你好，<span className="text-indigo-600">灵匠</span>
          </h2>
          <p className="text-lg text-gray-400 font-medium max-w-lg mx-auto leading-relaxed">
            在一个无限的创意空间中，捕捉灵感并将其转化为现实。
          </p>
        </div>

        {/* 核心输入框 - 保持与画布页一致的阴影和圆角 */}
        <div className="w-full relative">
          <form 
            onSubmit={handleSubmit}
            className="w-full bg-white/90 backdrop-blur-xl border border-gray-100 shadow-[0_32px_80px_-16px_rgba(0,0,0,0.08)] rounded-[32px] p-4 flex flex-col gap-4 focus-within:ring-4 focus-within:ring-indigo-500/5 transition-all"
          >
            <div className="px-4 pt-2">
              <textarea
                value={input}
                onChange={(e) => setInput(e.target.value)}
                placeholder={
                  activeMode === 'chat' ? "与灵匠对话，探索创意边界..." :
                  activeMode === 'image' ? "描述您想要创造的画面..." :
                  "构思一段惊艳的视觉序列..."
                }
                className="w-full text-xl font-medium text-gray-800 placeholder:text-gray-300 outline-none resize-none h-24 py-2 bg-transparent"
                onKeyDown={(e) => {
                  if (e.key === 'Enter' && !e.shiftKey) {
                    e.preventDefault();
                    handleSubmit(e);
                  }
                }}
              />
            </div>

            <div className="flex items-center justify-between px-2 pb-2">
              <div className="flex items-center gap-2.5">
                <button type="button" className="group w-10 h-10 border border-gray-100 rounded-xl flex items-center justify-center text-gray-400 hover:bg-gray-50 transition-all">
                  <Plus size={20} className="group-hover:rotate-90 transition-transform" />
                </button>
                
                {/* 模式切换器 - 灰色深色背景 */}
                <div className="flex items-center bg-[#4a4a4a] p-1 rounded-2xl gap-0.5">
                  <button 
                    type="button"
                    onClick={() => setActiveMode('chat')}
                    className={`w-9 h-9 flex items-center justify-center rounded-xl transition-all ${
                      activeMode === 'chat' ? 'bg-[#f3f4f6] text-gray-800 shadow-sm' : 'text-gray-400 hover:text-white'
                    }`}
                  >
                    <Bot size={18} />
                  </button>
                  <button 
                    type="button"
                    onClick={() => setActiveMode('image')}
                    className={`w-9 h-9 flex items-center justify-center rounded-xl transition-all ${
                      activeMode === 'image' ? 'bg-[#f3f4f6] text-gray-800 shadow-sm' : 'text-gray-400 hover:text-white'
                    }`}
                  >
                    <ImageIcon size={18} />
                  </button>
                  <button 
                    type="button"
                    onClick={() => setActiveMode('video')}
                    className={`w-9 h-9 flex items-center justify-center rounded-xl transition-all ${
                      activeMode === 'video' ? 'bg-[#f3f4f6] text-gray-800 shadow-sm' : 'text-gray-400 hover:text-white'
                    }`}
                  >
                    <Video size={18} />
                  </button>
                </div>
              </div>

              <button 
                type="submit"
                disabled={!input.trim()}
                className={`flex items-center gap-2 px-8 h-12 rounded-2xl font-black text-xs uppercase tracking-widest transition-all
                  ${input.trim() ? 'bg-black text-white hover:scale-105 active:scale-95 shadow-xl shadow-black/10' : 'bg-gray-100 text-gray-300 cursor-not-allowed'}`}
              >
                唤醒灵感
                <ArrowUp size={18} strokeWidth={3} />
              </button>
            </div>
          </form>
          
          {/* 快捷推荐 */}
          <div className="flex justify-center gap-2 mt-8">
            {['极简美学', '未来实验室', '中式山水', '机械之心'].map(tag => (
              <button 
                key={tag}
                onClick={() => setInput(tag)}
                className="px-4 py-1.5 bg-white border border-gray-100 rounded-full text-[11px] font-bold text-gray-400 hover:text-indigo-500 hover:border-indigo-100 transition-all"
              >
                #{tag}
              </button>
            ))}
          </div>
        </div>
      </main>

      {/* 底部版权/信息 */}
      <footer className="w-full p-8 text-center text-[10px] font-black uppercase tracking-[0.3em] text-gray-300 z-10">
        © 2025 LING JIANG LAB · CRAFTED WITH AI
      </footer>
    </div>
  );
};

export default HomePage;
