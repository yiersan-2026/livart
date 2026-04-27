import React, { useEffect, useMemo, useState } from 'react';
import { Check, DownloadCloud, Link2, Loader2, Search, X } from 'lucide-react';
import type { ExternalImageCandidate } from '../services/externalImages';
import { searchExternalImages } from '../services/externalImages';

const SUPPORTED_SOCIAL_PLATFORMS = [
  { label: '抖音', logo: '抖音', className: 'bg-zinc-950 text-white shadow-[inset_2px_0_0_#25f4ee,inset_-2px_0_0_#fe2c55]' },
  { label: '小红书', logo: '小红书', className: 'bg-[#ff2442] text-white' },
  { label: '微博', logo: '微博', className: 'bg-gradient-to-br from-[#ff8200] to-[#e6162d] text-white' },
  { label: '哔哩哔哩', logo: 'bilibili', className: 'bg-[#00a1d6] text-white' },
  { label: '知乎', logo: '知乎', className: 'bg-[#1772f6] text-white' }
];

const SUPPORTED_SOCIAL_PLATFORM_TEXT = SUPPORTED_SOCIAL_PLATFORMS.map(platform => platform.label).join('、');

interface ExternalImageImportModalProps {
  isOpen: boolean;
  isImporting: boolean;
  onClose: () => void;
  onImport: (candidates: ExternalImageCandidate[]) => Promise<void>;
}

const getCandidateTitle = (candidate: ExternalImageCandidate, index: number) => (
  candidate.formatLabel || candidate.title || `图片 ${index + 1}`
);

const ExternalImageImportModal: React.FC<ExternalImageImportModalProps> = ({
  isOpen,
  isImporting,
  onClose,
  onImport
}) => {
  const [sourceUrl, setSourceUrl] = useState('');
  const [images, setImages] = useState<ExternalImageCandidate[]>([]);
  const [selectedIds, setSelectedIds] = useState<string[]>([]);
  const [isSearching, setIsSearching] = useState(false);
  const [error, setError] = useState('');

  useEffect(() => {
    if (!isOpen) {
      setSourceUrl('');
      setImages([]);
      setSelectedIds([]);
      setError('');
      setIsSearching(false);
    }
  }, [isOpen]);

  const selectedImages = useMemo(() => {
    const selectedIdSet = new Set(selectedIds);
    return images.filter(image => selectedIdSet.has(image.id));
  }, [images, selectedIds]);

  if (!isOpen) return null;

  const handleSearch = async () => {
    const trimmedUrl = sourceUrl.trim();
    if (!trimmedUrl || isSearching) return;

    setIsSearching(true);
    setError('');
    setImages([]);
    setSelectedIds([]);
    try {
      const result = await searchExternalImages(trimmedUrl);
      setImages(result);
      setSelectedIds(result[0]?.id ? [result[0].id] : []);
      if (result.length === 0) {
        setError('没有从这个链接解析到图片');
      }
    } catch (searchError) {
      setError(searchError instanceof Error ? searchError.message : '解析社交媒体图片失败');
    } finally {
      setIsSearching(false);
    }
  };

  const handleToggleImage = (candidate: ExternalImageCandidate) => {
    setSelectedIds(prev => (
      prev.includes(candidate.id)
        ? prev.filter(id => id !== candidate.id)
        : [...prev, candidate.id]
    ));
  };

  const handleImport = async () => {
    if (selectedImages.length === 0 || isImporting) return;
    setError('');
    try {
      await onImport(selectedImages);
    } catch (importError) {
      setError(importError instanceof Error ? importError.message : '导入图片失败');
    }
  };

  return (
    <div className="fixed inset-0 z-[6000000] flex items-center justify-center bg-zinc-950/35 p-6 backdrop-blur-sm">
      <div className="flex max-h-[86vh] w-full max-w-4xl flex-col overflow-hidden rounded-[30px] border border-gray-100 bg-[#fbfaf7]">
        <div className="flex items-start justify-between gap-4 border-b border-gray-200 px-6 py-5">
          <div>
            <div className="flex items-center gap-2 text-lg font-black text-gray-950">
              <DownloadCloud size={20} />
              社交媒体图片
            </div>
            <div className="mt-2 flex flex-wrap items-center gap-2" aria-label="支持平台">
              {SUPPORTED_SOCIAL_PLATFORMS.map(platform => (
                <span
                  key={platform.label}
                  title={platform.label}
                  className={`flex h-7 items-center justify-center rounded-xl px-2.5 text-[11px] font-black leading-none ${platform.className}`}
                >
                  {platform.logo}
                </span>
              ))}
            </div>
          </div>
          <button
            type="button"
            onClick={onClose}
            disabled={isSearching || isImporting}
            className="rounded-2xl p-2 text-gray-400 transition-colors hover:bg-white hover:text-gray-900 disabled:opacity-40"
            aria-label="关闭"
          >
            <X size={18} />
          </button>
        </div>

        <div className="border-b border-gray-100 px-6 py-4">
          <div className="flex gap-2 rounded-2xl border border-gray-200 bg-white p-2">
            <div className="flex flex-1 items-center gap-2 px-2">
              <Link2 size={17} className="text-gray-400" />
              <input
                value={sourceUrl}
                onChange={(event) => setSourceUrl(event.target.value)}
                onKeyDown={(event) => {
                  if (event.key === 'Enter') {
                    event.preventDefault();
                    handleSearch();
                  }
                }}
                placeholder="粘贴抖音、小红书、微博、哔哩哔哩或知乎链接"
                className="h-10 flex-1 bg-transparent text-sm font-bold text-gray-900 outline-none placeholder:text-gray-300"
                disabled={isSearching || isImporting}
              />
            </div>
            <button
              type="button"
              onClick={handleSearch}
              disabled={!sourceUrl.trim() || isSearching || isImporting}
              className="flex h-10 items-center gap-2 rounded-xl bg-zinc-900 px-4 text-sm font-black text-white transition-colors hover:bg-zinc-700 disabled:cursor-not-allowed disabled:bg-gray-200"
            >
              {isSearching ? <Loader2 size={16} className="animate-spin" /> : <Search size={16} />}
              解析
            </button>
          </div>
          {error && (
            <div className="mt-3 rounded-2xl border border-red-100 bg-red-50 px-4 py-3 text-sm font-bold text-red-500">
              {error}
            </div>
          )}
        </div>

        <div className="min-h-[280px] flex-1 overflow-y-auto px-6 py-5">
          {isSearching ? (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              {Array.from({ length: 8 }).map((_, index) => (
                <div key={index} className="aspect-[4/5] animate-pulse bg-white ring-1 ring-gray-100" />
              ))}
            </div>
          ) : images.length > 0 ? (
            <div className="grid grid-cols-2 gap-4 md:grid-cols-4">
              {images.map((candidate, index) => {
                const selected = selectedIds.includes(candidate.id);
                return (
                  <button
                    key={`${candidate.id}-${candidate.url}`}
                    type="button"
                    onClick={() => handleToggleImage(candidate)}
                    aria-label={`选择${getCandidateTitle(candidate, index)}`}
                    title={getCandidateTitle(candidate, index)}
                    className={`group relative aspect-[4/5] overflow-hidden border bg-white p-2 transition-colors duration-150 ${
                      selected
                        ? 'border-zinc-900 ring-2 ring-zinc-900/10'
                        : 'border-gray-200 hover:border-gray-400'
                    }`}
                  >
                    <div className="flex h-full w-full items-center justify-center overflow-hidden bg-gray-50">
                      <img
                        src={candidate.thumbnailUrl || candidate.url}
                        alt={getCandidateTitle(candidate, index)}
                        className="max-h-full max-w-full object-contain"
                        loading="lazy"
                      />
                    </div>
                    {selected && (
                      <span className="absolute right-4 top-4 flex h-8 w-8 items-center justify-center rounded-full bg-zinc-900 text-white ring-4 ring-white">
                        <Check size={15} />
                      </span>
                    )}
                  </button>
                );
              })}
            </div>
          ) : (
            <div className="flex h-[280px] flex-col items-center justify-center rounded-[28px] border border-dashed border-gray-200 bg-white/70 text-center">
              <DownloadCloud size={34} className="text-gray-300" />
              <div className="mt-3 text-sm font-black text-gray-700">还没有解析图片</div>
              <div className="mt-1 text-xs font-bold text-gray-400">
                支持 {SUPPORTED_SOCIAL_PLATFORM_TEXT}，输入链接后点击“解析”。
              </div>
            </div>
          )}
        </div>

        <div className="flex items-center justify-between border-t border-gray-100 bg-white px-6 py-4">
          <div className="text-xs font-bold text-gray-400">
            已选择 {selectedImages.length} 张
          </div>
          <button
            type="button"
            onClick={handleImport}
            disabled={selectedImages.length === 0 || isImporting || isSearching}
            className="flex h-11 items-center gap-2 rounded-2xl bg-zinc-900 px-5 text-sm font-black text-white transition-colors hover:bg-zinc-700 disabled:cursor-not-allowed disabled:bg-gray-200"
          >
            {isImporting ? <Loader2 size={16} className="animate-spin" /> : <DownloadCloud size={16} />}
            导入到画布
          </button>
        </div>
      </div>
    </div>
  );
};

export default ExternalImageImportModal;
