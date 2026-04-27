import type { CanvasItem } from '../types';

const MAX_TITLE_LENGTH = 12;
const ID_PATTERN = /^[a-z0-9]{7,12}$/i;

const TITLE_STOP_WORDS = [
  'shot on',
  'full-frame',
  'mirrorless',
  'camera',
  'lens',
  'iso',
  'natural perspective',
  'realistic',
  'depth of field',
  'no cgi',
  'plastic skin',
  'over-sharpening',
  'distorted anatomy',
  '避免',
  '不要',
  '画幅比例要求',
  '未被蒙版覆盖',
  '必须保持原图不变'
];
const LOW_VALUE_TITLE_WORDS = [
  '皮肤',
  '白皙',
  '年龄',
  '岁',
  '身高',
  '体重',
  '斤',
  '身材',
  '头发',
  '发型',
  '红唇',
  '脸部',
  '线条',
  '甜美',
  '微笑',
  '眼神',
  '美甲',
  '低胸',
  '包臀',
  '丝袜',
  '高跟鞋',
  '抖音',
  '网红脸',
  '摄像机',
  '镜头',
  '光圈',
  '焦距'
];
const HIGH_VALUE_TITLE_WORDS = [
  '赛博朋克',
  '臭豆腐',
  '劳斯莱斯',
  '汽车',
  '车里',
  '后排',
  '车门',
  '夜景',
  '霓虹',
  '酒吧',
  '沙滩',
  '夏日',
  '城市',
  '日式',
  '和服',
  '小猫',
  '蝴蝶',
  '鞋子',
  '拖鞋',
  '桌子',
  '人物',
  '女性',
  '女模',
  '人像',
  '特写',
  '艺术照',
  '光追'
];

const TITLE_ACTION_REPLACEMENTS: Array<[RegExp, string]> = [
  [/把(.+?)换成(.+)/, '$2$1'],
  [/将(.+?)换成(.+)/, '$2$1'],
  [/换成(.+)/, '$1'],
  [/更换(.+)/, '$1'],
  [/删除(.+)/, '删除$1'],
  [/去掉(.+)/, '删除$1'],
  [/裁剪(.+)/, '$1裁剪'],
  [/放在(.+)/, '$1放置']
];

const cleanTitleText = (value: string) => value
  .replace(/@\w+/g, '')
  .replace(/[：:][\s\S]*$/g, match => (match.length > 36 ? '' : match))
  .replace(/\([^)]*\)/g, ' ')
  .replace(/（[^）]*）/g, ' ')
  .replace(/\b\d{1,3}(cm|mm|kg|岁|mm|s)\b/gi, ' ')
  .replace(/\bf\/?\d+(\.\d+)?\b/gi, ' ')
  .replace(/\b\d+\/\d+s\b/gi, ' ')
  .replace(/\b\d{1,5}\b/g, ' ')
  .replace(/(身高|体重|年龄|岁|斤|皮肤白皙|白皙|抖音|网红脸)/g, ' ')
  .replace(/[,.，。；;、｜|/\\]+/g, ' ')
  .replace(/\s+/g, ' ')
  .trim();

const inferCentralTitleFromPrompt = (value: string) => {
  const normalized = value.toLowerCase().replace(/\s+/g, '');
  const hasPerson = /(女性|女生|美女|人物|人像|模特|女孩|女人)/.test(normalized);
  const hasCar = /(车|汽车|轿车|劳斯莱斯|后排|车门|车内)/.test(normalized);
  const hasNight = /(夜晚|晚上|夜景|霓虹)/.test(normalized);
  if (hasPerson && hasCar) return hasNight ? '夜景车内人像' : '豪车后排人像';
  if (hasPerson && normalized.includes('酒吧')) return '酒吧人像';
  if (hasPerson && normalized.includes('沙滩')) return '沙滩人像';
  if (normalized.includes('赛博朋克') && normalized.includes('臭豆腐')) return '赛博朋克臭豆腐';
  if (normalized.includes('赛博朋克') && /(猫|小猫)/.test(normalized)) return '赛博朋克小猫';
  if (/(猫|小猫)/.test(normalized) && normalized.includes('蝴蝶')) return '小猫抓蝴蝶';
  return '';
};

const splitPromptSegments = (value: string) => (
  cleanTitleText(value)
    .split(/\s+/)
    .map(segment => segment.trim())
    .filter(Boolean)
    .filter(segment => !TITLE_STOP_WORDS.some(stopWord => segment.toLowerCase().includes(stopWord.toLowerCase())))
    .filter(segment => !/^(a|an|the|with|and|or|no|not|on|in|at|of|to)$/i.test(segment))
);

const compactTitle = (value: string) => {
  const normalized = cleanTitleText(value).replace(/\s+/g, '');
  if (!normalized) return '';
  return normalized.length > MAX_TITLE_LENGTH ? normalized.slice(0, MAX_TITLE_LENGTH) : normalized;
};

const normalizeTitleSegment = (segment: string) => (
  segment
    .replace(/^女生$/, '女性')
    .replace(/^美女$/, '女性')
    .replace(/^穿着/, '')
    .replace(/^身后/, '')
);

const getSegmentScore = (segment: string, index: number) => {
  const normalizedSegment = normalizeTitleSegment(segment);
  const highValueScore = HIGH_VALUE_TITLE_WORDS.some(word => normalizedSegment.includes(word)) ? 120 : 0;
  const lowValuePenalty = LOW_VALUE_TITLE_WORDS.some(word => normalizedSegment.includes(word)) ? -80 : 0;
  const chineseScore = /[\u4e00-\u9fff]/.test(normalizedSegment) ? 20 : 0;
  const lengthScore = normalizedSegment.length >= 2 && normalizedSegment.length <= 6 ? 12 : 0;
  return highValueScore + lowValuePenalty + chineseScore + lengthScore - index;
};

const buildTitleFromSegments = (segments: string[]) => {
  const normalizedSegments = segments.map(normalizeTitleSegment).filter(Boolean);
  const scoredSegments = normalizedSegments
    .map((segment, index) => ({ segment, index, score: getSegmentScore(segment, index) }))
    .filter(candidate => candidate.score > -20)
    .sort((left, right) => right.score - left.score || left.index - right.index)
    .slice(0, 4)
    .sort((left, right) => left.index - right.index)
    .map(candidate => candidate.segment);

  return compactTitle(scoredSegments.join(''));
};

export const generateImageTitleFromPrompt = (prompt: string, fallback = '未命名图片') => {
  const trimmedPrompt = prompt.trim();
  if (!trimmedPrompt) return fallback;

  const centralTitle = inferCentralTitleFromPrompt(trimmedPrompt);
  if (centralTitle) return centralTitle;

  const normalizedPrompt = cleanTitleText(trimmedPrompt);
  const actionTitle = TITLE_ACTION_REPLACEMENTS.reduce<string>((title, [pattern, replacement]) => {
    if (title) return title;
    const matchedTitle = normalizedPrompt.replace(pattern, replacement);
    return matchedTitle === normalizedPrompt ? '' : matchedTitle;
  }, '');

  if (actionTitle) {
    const title = compactTitle(actionTitle);
    if (title) return title;
  }

  const segments = splitPromptSegments(trimmedPrompt);
  const naturalTitle = buildTitleFromSegments(segments);
  if (naturalTitle) return naturalTitle;

  const strongSegments = segments.filter(segment => (
    /[\u4e00-\u9fff]/.test(segment) &&
    !/^(女生|美女|女性|人物|图片|原图|参考图|这张图|这个图|用这个|生成|编辑|重绘|局部|进行|一张)$/.test(segment)
  ));
  const selectedSegments = (strongSegments.length > 0 ? strongSegments : segments).slice(0, 5);
  const title = compactTitle(selectedSegments.join(''));
  return title || fallback;
};

const shouldRegenerateLabel = (label: string | undefined, item: CanvasItem) => {
  if (!label) return true;
  const normalizedLabel = label.trim();
  if (!normalizedLabel) return true;
  if (ID_PATTERN.test(normalizedLabel) || normalizedLabel === item.id) return true;
  if (/^AI\s*(生成|编辑|重绘|删除)中/.test(normalizedLabel)) return true;
  if (/(岁|斤|身高|体重|皮肤|白皙|抖音|网红脸|红唇|眼神|美甲|低胸|丝袜|高跟鞋)/.test(normalizedLabel)
      && !/(车|豪车|后排|车内|酒吧|沙滩|城市|夜景|霓虹|小猫|鞋|桌|人物|人像|去背景|删除|编辑|赛博朋克|臭豆腐)/.test(normalizedLabel)) return true;
  if (normalizedLabel.includes('@') || normalizedLabel.length > 18) return true;
  return false;
};

export const getCanvasItemDisplayTitle = (item: CanvasItem) => {
  if (item.type !== 'image') return item.label || item.id;

  if (!shouldRegenerateLabel(item.label, item)) {
    return item.label!;
  }

  return generateImageTitleFromPrompt(
    item.originalPrompt || item.prompt || item.optimizedPrompt || '',
    item.label || `图片${item.id.slice(0, 4)}`
  );
};

export const buildImageResultDescription = (title: string, mode: 'generated' | 'edited' = 'generated') => {
  const normalizedTitle = (title || '').trim() || '图片';
  const titleWithNoun = /(图|图片|照片|海报|插画|头像|作品|特写|人像|写真)$/.test(normalizedTitle)
    ? normalizedTitle
    : `${normalizedTitle}图片`;
  return mode === 'edited'
    ? `已为您完成这张${titleWithNoun}的编辑，已添加到画布中。`
    : `已为您生成这张${titleWithNoun}，已添加到画布中。`;
};
