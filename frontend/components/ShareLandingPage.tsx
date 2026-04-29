import React, { useEffect, useMemo, useState } from 'react';
import { Copy, Download, ExternalLink, Sparkles } from 'lucide-react';
import { parseLivartImageSharePageParams } from '../services/shareLinks';

const copyTextToClipboard = async (text: string) => {
  if (navigator.clipboard?.writeText && window.isSecureContext) {
    await navigator.clipboard.writeText(text);
    return;
  }

  const textarea = document.createElement('textarea');
  textarea.value = text;
  textarea.setAttribute('readonly', '');
  textarea.style.position = 'fixed';
  textarea.style.left = '-9999px';
  document.body.appendChild(textarea);
  textarea.select();
  const copied = document.execCommand('copy');
  textarea.remove();
  if (!copied) throw new Error('复制失败');
};

const ensureMetaTag = (property: string, content: string) => {
  let meta = document.head.querySelector<HTMLMetaElement>(`meta[property="${property}"]`);
  if (!meta) {
    meta = document.createElement('meta');
    meta.setAttribute('property', property);
    document.head.appendChild(meta);
  }
  meta.content = content;
};

const ShareLandingPage: React.FC = () => {
  const params = useMemo(() => parseLivartImageSharePageParams(), []);
  const [copyNotice, setCopyNotice] = useState('');
  const pageUrl = window.location.href;
  const shareText = `${params.title}\n${params.text}\n${pageUrl}`;

  useEffect(() => {
    document.title = `${params.title} - livart`;
    ensureMetaTag('og:title', `${params.title} - livart`);
    ensureMetaTag('og:description', params.text);
    ensureMetaTag('og:image', params.imageUrl);
    ensureMetaTag('og:url', pageUrl);
  }, [pageUrl, params.imageUrl, params.text, params.title]);

  const handleCopy = async () => {
    try {
      await copyTextToClipboard(shareText);
      setCopyNotice('分享文案已复制');
      window.setTimeout(() => setCopyNotice(''), 1800);
    } catch (error) {
      setCopyNotice(error instanceof Error ? error.message : '复制失败');
    }
  };

  if (!params.imageUrl) {
    return (
      <div className="flex min-h-screen items-center justify-center bg-[#fcfcfc] px-6 font-sans text-zinc-900">
        <div className="max-w-md rounded-3xl border border-zinc-100 bg-white p-6 text-center shadow-[0_30px_90px_-52px_rgba(0,0,0,0.45)]">
          <div className="text-lg font-black">分享链接无效</div>
          <div className="mt-2 text-sm font-bold leading-6 text-zinc-500">没有找到要分享的图片，请回到 livart 重新发起分享。</div>
          <a
            href="/"
            className="mt-5 inline-flex h-10 items-center justify-center rounded-2xl bg-zinc-950 px-5 text-sm font-black text-white"
          >
            打开 livart
          </a>
        </div>
      </div>
    );
  }

  return (
    <div className="min-h-screen bg-[#fcfcfc] px-4 py-6 font-sans text-zinc-900 sm:px-6 sm:py-10">
      <div className="mx-auto flex max-w-4xl flex-col gap-5">
        <div className="flex items-center justify-between gap-3">
          <a href="/" className="inline-flex items-center gap-2 text-sm font-black text-zinc-900">
            <span className="flex h-9 w-9 items-center justify-center rounded-2xl bg-zinc-950 text-white">
              <Sparkles size={17} />
            </span>
            livart
          </a>
          <a
            href="/"
            className="inline-flex h-9 items-center gap-1.5 rounded-xl border border-zinc-200 bg-white px-3 text-xs font-black text-zinc-700"
          >
            立即创作
            <ExternalLink size={13} />
          </a>
        </div>

        <div className="overflow-hidden rounded-[2rem] border border-zinc-100 bg-white shadow-[0_30px_90px_-58px_rgba(0,0,0,0.55)]">
          <div className="bg-zinc-950 px-5 py-4 text-white">
            <div className="text-base font-black">{params.title}</div>
            <div className="mt-1 text-xs font-bold leading-5 text-zinc-300">{params.text}</div>
          </div>
          <div className="bg-zinc-100 p-3 sm:p-5">
            <img
              src={params.imageUrl}
              alt={params.title}
              className="mx-auto max-h-[72vh] max-w-full bg-white object-contain"
            />
          </div>
          <div className="flex flex-col gap-2 border-t border-zinc-100 p-4 sm:flex-row">
            <button
              type="button"
              onClick={handleCopy}
              className="inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-2xl bg-zinc-950 px-4 text-sm font-black text-white"
            >
              <Copy size={16} />
              {copyNotice || '复制分享文案'}
            </button>
            <a
              href={params.imageUrl}
              download
              className="inline-flex h-11 flex-1 items-center justify-center gap-2 rounded-2xl border border-zinc-200 bg-white px-4 text-sm font-black text-zinc-800"
            >
              <Download size={16} />
              下载图片
            </a>
          </div>
        </div>

        <div className="rounded-3xl border border-emerald-100 bg-emerald-50 px-4 py-3 text-xs font-bold leading-6 text-emerald-800">
          如果你在微信里打开了这个页面，请点击右上角“…”分享给好友或朋友圈。PC 扫码时必须扫这个页面链接，不能扫图片裸链。
        </div>
      </div>
    </div>
  );
};

export default ShareLandingPage;
