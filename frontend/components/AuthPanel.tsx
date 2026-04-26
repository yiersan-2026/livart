import React, { useState } from 'react';
import { Hammer, Loader2, LogIn, UserPlus } from 'lucide-react';
import { AuthSession, login, register } from '../services/auth';
import ProjectLinks from './ProjectLinks';

interface AuthPanelProps {
  onAuthenticated: (session: AuthSession) => void;
}

const AuthPanel: React.FC<AuthPanelProps> = ({ onAuthenticated }) => {
  const [mode, setMode] = useState<'login' | 'register'>('login');
  const [username, setUsername] = useState('');
  const [displayName, setDisplayName] = useState('');
  const [password, setPassword] = useState('');
  const [isSubmitting, setIsSubmitting] = useState(false);
  const [error, setError] = useState('');

  const handleSubmit = async (event: React.FormEvent) => {
    event.preventDefault();
    setError('');
    setIsSubmitting(true);

    try {
      const session = mode === 'login'
        ? await login(username, password)
        : await register(username, password, displayName);
      onAuthenticated(session);
    } catch (authError) {
      setError(authError instanceof Error ? authError.message : '登录失败，请稍后重试');
    } finally {
      setIsSubmitting(false);
    }
  };

  const isRegisterMode = mode === 'register';

  return (
    <div className="flex h-screen items-center justify-center bg-[#fcfcfc] px-6 font-sans text-gray-900">
      <div className="w-full max-w-md rounded-[32px] border border-gray-100 bg-white p-8 shadow-[0_40px_100px_-32px_rgba(0,0,0,0.22)]">
        <div className="mb-8 flex items-center justify-between gap-4">
          <div className="flex min-w-0 items-center gap-3">
            <div className="flex h-12 w-12 shrink-0 items-center justify-center rounded-2xl bg-black shadow-lg">
              <Hammer className="text-white" size={20} />
            </div>
            <div className="min-w-0">
              <h1 className="text-2xl font-black tracking-tighter">livart</h1>
              <p className="truncate text-xs font-bold text-gray-400">登录后永久保存你的画布历史</p>
            </div>
          </div>
          <ProjectLinks />
        </div>

        <form onSubmit={handleSubmit} className="space-y-4">
          <div>
            <label className="mb-2 block text-sm font-black text-gray-700">用户名</label>
            <input
              value={username}
              onChange={(event) => setUsername(event.target.value)}
              autoComplete="username"
              placeholder="请输入用户名"
              className="w-full rounded-2xl border border-gray-200 px-4 py-3 text-sm font-bold outline-none transition-all focus:border-gray-300 focus:ring-4 focus:ring-black/5"
            />
          </div>

          {isRegisterMode && (
            <div>
              <label className="mb-2 block text-sm font-black text-gray-700">显示名称</label>
              <input
                value={displayName}
                onChange={(event) => setDisplayName(event.target.value)}
                autoComplete="name"
                placeholder="可选"
                className="w-full rounded-2xl border border-gray-200 px-4 py-3 text-sm font-bold outline-none transition-all focus:border-gray-300 focus:ring-4 focus:ring-black/5"
              />
            </div>
          )}

          <div>
            <label className="mb-2 block text-sm font-black text-gray-700">密码</label>
            <input
              type="password"
              value={password}
              onChange={(event) => setPassword(event.target.value)}
              autoComplete={isRegisterMode ? 'new-password' : 'current-password'}
              placeholder={isRegisterMode ? '至少 6 位' : '请输入密码'}
              className="w-full rounded-2xl border border-gray-200 px-4 py-3 text-sm font-bold outline-none transition-all focus:border-gray-300 focus:ring-4 focus:ring-black/5"
            />
          </div>

          {error && (
            <div className="rounded-2xl border border-red-100 bg-red-50 px-4 py-3 text-sm font-bold text-red-500">
              {error}
            </div>
          )}

          <button
            type="submit"
            disabled={isSubmitting || !username.trim() || password.length < 6}
            className="flex w-full items-center justify-center gap-2 rounded-2xl bg-black px-4 py-3 text-sm font-black text-white shadow-lg transition-all hover:opacity-90 active:scale-[0.99] disabled:opacity-30"
          >
            {isSubmitting ? (
              <Loader2 size={18} className="animate-spin" />
            ) : isRegisterMode ? (
              <UserPlus size={18} />
            ) : (
              <LogIn size={18} />
            )}
            {isRegisterMode ? '注册并进入画布' : '登录'}
          </button>
        </form>

        <button
          type="button"
          onClick={() => {
            setMode(isRegisterMode ? 'login' : 'register');
            setError('');
          }}
          className="mt-5 w-full text-center text-sm font-bold text-gray-400 transition-colors hover:text-gray-700"
        >
          {isRegisterMode ? '已有账号？去登录' : '还没有账号？立即注册'}
        </button>
      </div>
    </div>
  );
};

export default AuthPanel;
