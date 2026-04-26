import React from 'react';
import { Github } from 'lucide-react';

const PROJECT_LINKS = {
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
    <rect width="24" height="24" rx="6" fill="#C71D23" />
    <path
      d="M7.5 5.5h9A2.5 2.5 0 0 1 19 8v8a2.5 2.5 0 0 1-2.5 2.5h-9A2.5 2.5 0 0 1 5 16V8a2.5 2.5 0 0 1 2.5-2.5Zm1.25 3.25v6.5h6.1v-2.05h-3.7v-1.45h3.7V9.7h-3.7v-.95h5V8a.75.75 0 0 0-.75-.75H8.75Z"
      fill="white"
    />
  </svg>
);

interface ProjectLinksProps {
  className?: string;
}

const ProjectLinks: React.FC<ProjectLinksProps> = ({ className = '' }) => (
  <div className={`flex items-center gap-1.5 rounded-2xl border border-gray-100 bg-white/90 p-1 backdrop-blur-2xl ${className}`}>
    <a
      href={PROJECT_LINKS.gitee}
      target="_blank"
      rel="noreferrer"
      className="flex h-9 w-9 items-center justify-center rounded-xl text-gray-400 transition-all hover:bg-red-50 hover:text-red-600 active:scale-95"
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
