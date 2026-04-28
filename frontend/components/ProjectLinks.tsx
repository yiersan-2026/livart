import React from 'react';
import { Github } from 'lucide-react';

const PROJECT_LINKS = {
  stickers: 'https://haohuo.jinritemai.com/ecommerce/trade/detail/index.html?id=3816860766594793858&origin_type=604',
  gitee: 'https://gitee.com/sunowen/livart',
  github: 'https://github.com/yiersan-2026/livart'
};

const GiteeLogo: React.FC<{ size?: number }> = ({ size = 18 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    role="img"
    aria-hidden="true"
  >
    <path
      d="M7.5 5.5h9A2.5 2.5 0 0 1 19 8v8a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 16V8a2.5 2.5 0 0 1 2.5-2.5Zm1.25 3.25v6.5h6.1v-2.05h-3.7v-1.45h3.7V9.7h-3.7v-.95h5V8a.75.75 0 0 0-.75-.75H8.75Z"
      fill="currentColor"
    />
  </svg>
);

const StickerProductLogo: React.FC<{ size?: number }> = ({ size = 20 }) => (
  <svg
    width={size}
    height={size}
    viewBox="0 0 24 24"
    role="img"
    aria-hidden="true"
  >
    <rect x="3" y="3" width="18" height="18" rx="5" fill="currentColor" opacity="0.12" />
    <circle cx="8" cy="8" r="3" fill="currentColor" opacity="0.55" />
    <circle cx="16.5" cy="7.5" r="2.5" fill="currentColor" opacity="0.4" />
    <path d="M5.5 16.5c2.2-4.4 5.3-5 7.7-1.6 1.3-1.7 3.2-2.1 5.3 1.6v1.7a2.3 2.3 0 0 1-2.3 2.3H7.8a2.3 2.3 0 0 1-2.3-2.3v-1.7Z" fill="currentColor" />
    <path d="M15.4 20.5 20.5 15v2.9a2.6 2.6 0 0 1-2.6 2.6h-2.5Z" fill="currentColor" opacity="0.7" />
    <path d="M7.7 13.8h8.6" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" opacity="0.75" />
    <path d="M9.3 16.8h5.4" stroke="currentColor" strokeWidth="1.8" strokeLinecap="round" opacity="0.75" />
  </svg>
);

interface ProjectLinksProps {
  className?: string;
}

const ProjectLinks: React.FC<ProjectLinksProps> = ({ className = '' }) => (
  <div className={`flex items-center gap-1.5 rounded-2xl border border-gray-100 bg-white/90 p-1 backdrop-blur-2xl ${className}`}>
    <a
      href={PROJECT_LINKS.stickers}
      target="_blank"
      rel="noreferrer"
      className="flex h-9 w-9 items-center justify-center rounded-xl text-gray-500 transition-all hover:bg-gray-100 hover:text-gray-900 active:scale-95"
      title="热门AI产品贴纸"
      aria-label="热门AI产品贴纸"
    >
      <StickerProductLogo />
    </a>
    <a
      href={PROJECT_LINKS.gitee}
      target="_blank"
      rel="noreferrer"
      className="flex h-9 w-9 items-center justify-center rounded-xl text-gray-400 transition-all hover:bg-gray-100 hover:text-gray-700 active:scale-95"
      title="打开 Gitee 项目主页"
      aria-label="打开 Gitee 项目主页"
    >
      <GiteeLogo />
    </a>
    <a
      href={PROJECT_LINKS.github}
      target="_blank"
      rel="noreferrer"
      className="flex h-9 w-9 items-center justify-center rounded-xl text-gray-500 transition-all hover:bg-gray-100 hover:text-gray-900 active:scale-95"
      title="打开 GitHub 项目主页"
      aria-label="打开 GitHub 项目主页"
    >
      <Github size={19} strokeWidth={2.3} />
    </a>
  </div>
);

export default ProjectLinks;
