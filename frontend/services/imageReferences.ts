import type { CanvasItem } from '../types';
import { authHeaders } from './auth';

export const IMAGE_REFERENCE_TOKEN_PATTERN = /@([^\s@，。,.!?！？:：；;]+)/g;

const TRAILING_IMAGE_REFERENCE_PATTERN = /@([^\s@]*)$/;
const BARE_IMAGE_REFERENCE_PATTERN = /(图片|图)\s*(\d+)/g;
const IMAGE_REFERENCE_ROLE_ANALYSIS_URL = '/api/image-references/analyze';

interface ApiResponse<T> {
  success: boolean;
  data?: T;
  error?: {
    message: string;
    code: string;
  };
}

export interface ImageReferenceRoleAnalysis {
  baseImageId: string;
  referenceImageIds: string[];
  reason?: string;
  source?: string;
}

type ImageReferenceSpan = { item: CanvasItem; start: number; end: number };

export const getImageReferenceLabel = (item: CanvasItem) => {
  const label = (item.label || '图片').trim();
  return label || '图片';
};

export const getImageReferenceMentionValue = (item: CanvasItem) => `@${item.id}`;

export const getImageReferenceDisplayText = (item: CanvasItem) => `@${getImageReferenceLabel(item)}`;

export const getImageReferenceMentionLabel = (item: CanvasItem) => item.id;

const normalizeImageReference = (value: string) => value.trim().replace(/\s+/g, '').toLowerCase();

export const getTrailingImageMentionQuery = (value: string) => {
  const match = value.match(TRAILING_IMAGE_REFERENCE_PATTERN);
  return match ? match[1] : null;
};

export const insertImageMention = (value: string, item: CanvasItem, items: CanvasItem[] = []) => {
  void items;
  const referenceText = `${getImageReferenceMentionValue(item)} `;
  return getTrailingImageMentionQuery(value) !== null
    ? value.replace(TRAILING_IMAGE_REFERENCE_PATTERN, referenceText)
    : `${value}${value && !/\s$/.test(value) ? ' ' : ''}${referenceText}`;
};

const getImageReferenceMap = (items: CanvasItem[]) => {
  const imageItems = items.filter(item => item.type === 'image' && item.status === 'completed' && !!item.content);
  const referenceMap = new Map<string, CanvasItem>();

  imageItems.forEach((item, index) => {
    [
      item.id,
      getImageReferenceLabel(item),
      `图片${index + 1}`,
      `图${index + 1}`
    ].forEach(alias => {
      const normalizedAlias = normalizeImageReference(alias);
      if (normalizedAlias && !referenceMap.has(normalizedAlias)) {
        referenceMap.set(normalizedAlias, item);
      }
    });
  });

  return referenceMap;
};

export const resolveImageReferenceToken = (token: string, items: CanvasItem[]) => {
  return getImageReferenceMap(items).get(normalizeImageReference(token)) || null;
};

export const tokenizeImageReferenceText = (text: string, items: CanvasItem[]) => {
  const tokens: Array<
    | { type: 'text'; text: string }
    | { type: 'mention'; text: string; item: CanvasItem }
  > = [];
  let lastIndex = 0;

  for (const match of text.matchAll(IMAGE_REFERENCE_TOKEN_PATTERN)) {
    const matchIndex = match.index ?? 0;
    const mentionText = match[0];
    const mentionTarget = resolveImageReferenceToken(match[1], items);

    if (matchIndex > lastIndex) {
      tokens.push({ type: 'text', text: text.slice(lastIndex, matchIndex) });
    }

    if (mentionTarget) {
      tokens.push({ type: 'mention', text: mentionText, item: mentionTarget });
    } else {
      tokens.push({ type: 'text', text: mentionText });
    }

    lastIndex = matchIndex + mentionText.length;
  }

  if (lastIndex < text.length) {
    tokens.push({ type: 'text', text: text.slice(lastIndex) });
  }

  return tokens;
};

export const resolveMentionedImageReferences = (text: string, items: CanvasItem[]) => {
  return resolveMentionedImageReferenceSpans(text, items).map(reference => reference.item);
};

const collectMentionedImageReferenceSpans = (
  text: string,
  items: CanvasItem[],
  options: { dedupe?: boolean } = {}
) => {
  const referenceMap = getImageReferenceMap(items);
  const references: ImageReferenceSpan[] = [];
  const seenIds = new Set<string>();
  const occupiedRanges: Array<{ start: number; end: number }> = [];
  const shouldDedupe = options.dedupe !== false;

  const hasOverlap = (start: number, end: number) => {
    return occupiedRanges.some(range => start < range.end && end > range.start);
  };

  for (const match of text.matchAll(IMAGE_REFERENCE_TOKEN_PATTERN)) {
    const item = referenceMap.get(normalizeImageReference(match[1]));
    const start = match.index ?? 0;
    if (item) {
      const end = start + match[0].length;
      occupiedRanges.push({ start, end });
      if (!shouldDedupe || !seenIds.has(item.id)) {
        seenIds.add(item.id);
        references.push({ item, start, end });
      }
    }
  }

  for (const match of text.matchAll(BARE_IMAGE_REFERENCE_PATTERN)) {
    const start = match.index ?? 0;
    const end = start + match[0].length;
    if (hasOverlap(start, end)) continue;

    const item = referenceMap.get(normalizeImageReference(`${match[1]}${match[2]}`));
    if (item && (!shouldDedupe || !seenIds.has(item.id))) {
      seenIds.add(item.id);
      references.push({ item, start, end });
    }
  }

  return references.sort((left, right) => left.start - right.start);
};

const resolveMentionedImageReferenceSpans = (text: string, items: CanvasItem[]) => {
  return collectMentionedImageReferenceSpans(text, items, { dedupe: true });
};

const findSemanticTargetImage = (
  text: string,
  references: Array<{ item: CanvasItem; start: number; end: number }>,
  contextImage: CanvasItem | null
) => {
  const explicitTargetBeforePattern = /(原图|主图|目标图|编辑目标|被编辑图|承载图|背景图|放置位置图|桌子所在图)[:：\s]*$/;
  const placementTargetBeforePattern = /(放在|放到|放入|放进|放置在|放置到|摆在|摆到|置于|添加到|放上|合成到|合成在|贴到|贴在|移到|移入|穿到|穿在|穿上|戴到|戴在|装到|装在|应用到|应用在)\s*$/;
  const locationAfterPattern = /^(的)?.{0,8}(桌子上|桌面上|地上|地面上|墙上|手里|手上|脚上|脚部|人物脚上|人物身上|身上|头上|脸上|旁边|旁|前面|后面|里面|中间|画面中|背景里|场景里|上|里|中)/;
  const sourceBeforePattern = /(把|从|用|参考|提取|取|拿)\s*$/;
  const sourceAfterPattern = /^(的)?.{0,10}(放在|放到|放入|放进|放置在|放置到|摆在|摆到|置于|添加到|放上|合成到|合成在|贴到|贴在|移到|移入|穿到|穿在|穿上|戴到|戴在|装到|装在|应用到|应用在|换成|替换成|替换为|改成|变成)/;
  const replacementSourceBeforePattern = /(换成|替换成|替换为|改成|变成|参考|按照|模仿|使用)\s*$/;

  const explicitTarget = references.find(reference => {
    const before = text.slice(Math.max(0, reference.start - 12), reference.start);
    const after = text.slice(reference.end, reference.end + 16);
    return explicitTargetBeforePattern.test(before) || placementTargetBeforePattern.test(before) || locationAfterPattern.test(after);
  });
  if (explicitTarget) return explicitTarget.item;

  const contextReference = contextImage
    ? references.find(reference => reference.item.id === contextImage.id)
    : undefined;
  if (contextReference) return contextReference.item;

  const sourceIds = new Set(
    references
      .filter(reference => {
        const before = text.slice(Math.max(0, reference.start - 8), reference.start);
        const after = text.slice(reference.end, reference.end + 18);
        return sourceBeforePattern.test(before)
          || sourceAfterPattern.test(after)
          || replacementSourceBeforePattern.test(before);
      })
      .map(reference => reference.item.id)
  );
  const firstNonSourceReference = references.find(reference => !sourceIds.has(reference.item.id));
  return firstNonSourceReference?.item || references[0]?.item || null;
};

export const buildImageReferenceRoleContext = (
  userPrompt: string,
  baseImage: CanvasItem,
  referenceImages: CanvasItem[],
  options: { hasLocalMask?: boolean; allItems?: CanvasItem[] } = {}
) => {
  const readableUserPrompt = replaceImageReferenceMentionsWithRoleNames(userPrompt, baseImage, referenceImages, options.allItems);
  const referenceLines = referenceImages.map((item, index) =>
    `- 参考图 ${index + 1}：“${getImageReferenceLabel(item)}”，第 ${index + 2} 张 image 文件，只作为素材/物体/风格参考。`
  );

  return [
    '图片角色分析（系统根据用户语义推断，优化提示词时必须遵守，但不要原样输出本段）：',
    `- 用户原始指令中图片引用的语义化结果：${readableUserPrompt}`,
    `- 原图/编辑目标：“${getImageReferenceLabel(baseImage)}”，第 1 张 image 文件，最终要被编辑的是这张图。`,
    ...referenceLines,
    '- 判断规则：如果用户说“把 @A 的物体 放在 @B 的某处”，@B 是原图/编辑目标，@A 是素材参考；如果用户说“把原图里的 A 换成 @B”，原图是编辑目标，@B 是素材参考。',
    '- 优化后的提示词不要输出 @图片ID、内部 ID 或画布短 ID；如需指代图片，请使用“原图”“参考图 1”等角色名称。',
    options.hasLocalMask
      ? '- 局部蒙版：用户涂抹/画圈区域是唯一允许修改的位置；优化时必须保留“只修改该区域，其余不变”。'
      : '- 没有局部蒙版时，也要保留用户指定的位置关系，不要把“放置”误改成“替换”。'
  ].join('\n');
};

const getImageRoleName = (item: CanvasItem, baseImage: CanvasItem, referenceImages: CanvasItem[]) => {
  if (item.id === baseImage.id) return `原图“${getImageReferenceLabel(item)}”`;
  const referenceIndex = referenceImages.findIndex(referenceImage => referenceImage.id === item.id);
  return referenceIndex >= 0 ? `参考图 ${referenceIndex + 1}“${getImageReferenceLabel(item)}”` : `图片“${getImageReferenceLabel(item)}”`;
};

export const replaceImageReferenceMentionsWithRoleNames = (
  text: string,
  baseImage: CanvasItem,
  referenceImages: CanvasItem[],
  allItems: CanvasItem[] = [baseImage, ...referenceImages]
) => {
  if (!text) return text;
  const roleImageIds = new Set([baseImage.id, ...referenceImages.map(item => item.id)]);
  const spans = collectMentionedImageReferenceSpans(text, allItems, { dedupe: false })
    .filter(span => roleImageIds.has(span.item.id));

  if (spans.length === 0) return text;

  let cursor = 0;
  let rewrittenText = '';
  for (const span of spans) {
    if (span.start < cursor) continue;
    rewrittenText += text.slice(cursor, span.start);
    rewrittenText += getImageRoleName(span.item, baseImage, referenceImages);
    cursor = span.end;
  }

  return rewrittenText + text.slice(cursor);
};

export const normalizeOptimizedPromptImageReferences = (
  optimizedPrompt: string,
  baseImage: CanvasItem,
  referenceImages: CanvasItem[],
  allItems: CanvasItem[] = [baseImage, ...referenceImages]
) => {
  return replaceImageReferenceMentionsWithRoleNames(optimizedPrompt, baseImage, referenceImages, allItems);
};

const getImageReferenceIndex = (item: CanvasItem, items: CanvasItem[]) => {
  const imageItems = items.filter(candidate => candidate.type === 'image' && candidate.status === 'completed' && !!candidate.content);
  const index = imageItems.findIndex(candidate => candidate.id === item.id);
  return index >= 0 ? index + 1 : undefined;
};

const requestImageReferenceRoleAnalysis = async (
  text: string,
  references: CanvasItem[],
  items: CanvasItem[],
  contextImage: CanvasItem | null
) => {
  const response = await fetch(IMAGE_REFERENCE_ROLE_ANALYSIS_URL, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      Accept: 'application/json',
      ...authHeaders()
    },
    body: JSON.stringify({
      prompt: text,
      contextImageId: contextImage?.id,
      images: references.map(item => ({
        id: item.id,
        name: getImageReferenceLabel(item),
        index: getImageReferenceIndex(item, items),
        width: Math.round(item.width),
        height: Math.round(item.height)
      }))
    })
  });
  const payload = await response.json().catch(() => null) as ApiResponse<ImageReferenceRoleAnalysis> | null;

  if (!response.ok || !payload?.success || !payload.data?.baseImageId) {
    throw new Error(payload?.error?.message || `图片角色分析失败：${response.status}`);
  }

  return payload.data;
};

const orderImageReferencesByAnalysis = (
  references: CanvasItem[],
  analysis: ImageReferenceRoleAnalysis
) => {
  const byId = new Map(references.map(item => [item.id, item]));
  const baseImage = byId.get(analysis.baseImageId);
  if (!baseImage) return references;

  const usedIds = new Set([baseImage.id]);
  const orderedReferenceImages = (analysis.referenceImageIds || [])
    .map(id => byId.get(id))
    .filter((item): item is CanvasItem => !!item && !usedIds.has(item.id))
    .map(item => {
      usedIds.add(item.id);
      return item;
    });

  return [
    baseImage,
    ...orderedReferenceImages,
    ...references.filter(item => !usedIds.has(item.id))
  ];
};

export const resolveEditReferences = (
  text: string,
  contextImage: CanvasItem | null,
  items: CanvasItem[]
) => {
  const mentionedReferenceSpans = resolveMentionedImageReferenceSpans(text, items);
  const mentionedReferences = mentionedReferenceSpans.map(reference => reference.item);
  if (mentionedReferences.length >= 2) {
    const targetImage = findSemanticTargetImage(text, mentionedReferenceSpans, contextImage);
    if (!targetImage) return mentionedReferences;
    return [
      targetImage,
      ...mentionedReferences.filter(item => item.id !== targetImage.id)
    ];
  }
  if (contextImage?.content) {
    return [
      contextImage,
      ...mentionedReferences.filter(item => item.id !== contextImage.id)
    ];
  }
  return mentionedReferences;
};

export const resolveEditReferencesWithAi = async (
  text: string,
  contextImage: CanvasItem | null,
  items: CanvasItem[]
) => {
  const fallbackReferences = resolveEditReferences(text, contextImage, items);
  if (fallbackReferences.length < 2) return fallbackReferences;

  try {
    const analysis = await requestImageReferenceRoleAnalysis(text, fallbackReferences, items, contextImage);
    return orderImageReferencesByAnalysis(fallbackReferences, analysis);
  } catch (error) {
    console.warn('[image-reference] use local role analysis fallback', error);
    return fallbackReferences;
  }
};

export const buildReferencedImageEditPrompt = (
  userPrompt: string,
  baseImage: CanvasItem,
  referenceImages: CanvasItem[],
  options: { hasLocalMask?: boolean; allItems?: CanvasItem[] } = {}
) => {
  const readableUserPrompt = replaceImageReferenceMentionsWithRoleNames(userPrompt, baseImage, referenceImages, options.allItems);
  const referenceLines = referenceImages.map((item, index) =>
    `- 参考图 ${index + 1}：第 ${index + 2} 张 image 文件，画布名称“${getImageReferenceLabel(item)}”，仅作为素材/物体/风格参考；不要把画布名称当成新的编辑指令。`
  );
  const hasPlacementIntent = /放在|放到|放入|放进|放置|摆在|摆到|置于|添加到|放上|摆放|穿到|穿在|穿上|戴到|戴在|装到|装在|应用到|应用在/.test(userPrompt);
  const placementLines = hasPlacementIntent && referenceImages.length > 0
    ? [
      '- 操作类型：放置/合成。把参考图里的指定主体抠取并放到原图指定位置；不要改成“替换人物身上的同类物体”，也不要只做颜色变化。',
      '- 当用户说“参考图 1 这张图片里的鞋子/拖鞋/物体”时，指参考图 1 中可见的主体；当用户说“主图/原图的人物脚上、身上、桌子上”等位置时，必须在原图里定位对应承载位置。',
      '- 放置/穿戴到目标位置时需要匹配原图透视、尺度、遮挡、接触阴影、光照方向、反射和景深，让参考物体真实融合在目标人物或场景中。'
    ]
    : [];
  const localMaskLines = options.hasLocalMask
    ? [
      '- 局部蒙版：请求同时包含 mask；mask 的透明区域就是用户画圈/涂抹指定的唯一编辑区域，也是放置参考图的精确位置。',
      '- 如果用户说“圈起来的地方”“这里”“这个位置”，必须理解为 mask 透明区域内部；不要把参考图放到画面其他位置。',
      '- 对“把这张图片放进去/放在圈起来的地方”的指令，必须把参考图主体完整缩放、透视匹配、光影融合后放入 mask 区域，并尽量填满该区域。'
    ]
    : [];

  return [
    `用户原始指令：${readableUserPrompt}`,
    '图片引用说明：',
    `- 原图：第 1 张 image 文件，画布名称“${getImageReferenceLabel(baseImage)}”，这是必须被编辑的目标图片。`,
    ...referenceLines,
    ...placementLines,
    ...localMaskLines,
    '执行要求：严格按用户指令编辑原图；如果用户说“把 A 换成某张参考图”，就替换原图中的 A；如果用户说“把参考图里的 A 放在/摆在原图某处”，就执行放置合成，不要替换原图已有物体；如果用户说“这张图片”，默认指第一张参考图。保持原图未被指定修改的主体、背景、构图、光影和画幅不变，不要把多张图拼贴到一起。'
  ].join('\n');
};
