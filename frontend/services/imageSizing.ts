import type { ImageAspectRatio, ImageResolution } from '../types';

export const DEFAULT_GENERATED_IMAGE_LONG_SIDE = 512;

export const IMAGE_ASPECT_RATIO_OPTIONS: Array<{
  value: ImageAspectRatio;
  label: string;
  title: string;
}> = [
  { value: 'auto', label: '自动', title: '自动：沿用参考图或模型默认画幅' },
  { value: '1:1', label: '1:1', title: '方图' },
  { value: '4:3', label: '4:3', title: '横向标准画幅' },
  { value: '3:4', label: '3:4', title: '竖向标准画幅' },
  { value: '16:9', label: '16:9', title: '横向宽屏画幅' },
  { value: '9:16', label: '9:16', title: '竖向手机画幅' }
];

export const IMAGE_RESOLUTION_OPTIONS: Array<{
  value: ImageResolution;
  label: string;
  title: string;
}> = [
  { value: '1k', label: '1K', title: '轻量生成，速度更快' },
  { value: '2k', label: '2K', title: '默认高清，兼顾质量和速度' },
  { value: '4k', label: '4K', title: '超高清，耗时更长，部分画幅会使用最大安全尺寸' }
];

type GeminiAspectRatio = '1:1' | '4:3' | '3:4' | '16:9' | '9:16';

interface FrameDimensions {
  width: number;
  height: number;
}

interface FramePosition extends FrameDimensions {
  x: number;
  y: number;
}

const ASPECT_RATIO_DIMENSIONS: Record<Exclude<ImageAspectRatio, 'auto'>, FrameDimensions> = {
  '1:1': { width: 1, height: 1 },
  '4:3': { width: 4, height: 3 },
  '3:4': { width: 3, height: 4 },
  '16:9': { width: 16, height: 9 },
  '9:16': { width: 9, height: 16 }
};

const getCanvasDimension = (value: number) => Math.max(1, Math.round(value));

export const fitDimensionsToLongSide = (
  width: number,
  height: number,
  maxLongSide = DEFAULT_GENERATED_IMAGE_LONG_SIDE
): FrameDimensions => {
  const safeWidth = getCanvasDimension(width);
  const safeHeight = getCanvasDimension(height);
  const safeMaxLongSide = getCanvasDimension(maxLongSide);
  const scale = Math.min(safeMaxLongSide / Math.max(safeWidth, safeHeight), 1);

  return {
    width: getCanvasDimension(safeWidth * scale),
    height: getCanvasDimension(safeHeight * scale)
  };
};

export const getAspectRatioFrame = (
  aspectRatio: ImageAspectRatio,
  fallbackWidth = DEFAULT_GENERATED_IMAGE_LONG_SIDE,
  fallbackHeight = DEFAULT_GENERATED_IMAGE_LONG_SIDE,
  maxLongSide = DEFAULT_GENERATED_IMAGE_LONG_SIDE
): FrameDimensions => {
  if (aspectRatio === 'auto') {
    return fitDimensionsToLongSide(fallbackWidth, fallbackHeight, maxLongSide);
  }

  const ratio = ASPECT_RATIO_DIMENSIONS[aspectRatio];
  if (ratio.width >= ratio.height) {
    return {
      width: getCanvasDimension(maxLongSide),
      height: getCanvasDimension((maxLongSide * ratio.height) / ratio.width)
    };
  }

  return {
    width: getCanvasDimension((maxLongSide * ratio.width) / ratio.height),
    height: getCanvasDimension(maxLongSide)
  };
};

export const inferAspectRatioFromDimensions = (
  width: number,
  height: number
): Exclude<ImageAspectRatio, 'auto'> => {
  const safeWidth = getCanvasDimension(width);
  const safeHeight = getCanvasDimension(height);
  const sourceRatio = safeWidth / safeHeight;

  return (Object.entries(ASPECT_RATIO_DIMENSIONS) as Array<[Exclude<ImageAspectRatio, 'auto'>, FrameDimensions]>)
    .reduce((bestMatch, [aspectRatio, dimensions]) => {
      const candidateRatio = dimensions.width / dimensions.height;
      const candidateDistance = Math.abs(Math.log(sourceRatio / candidateRatio));
      if (candidateDistance < bestMatch.distance) {
        return { aspectRatio, distance: candidateDistance };
      }
      return bestMatch;
    }, {
      aspectRatio: '1:1' as Exclude<ImageAspectRatio, 'auto'>,
      distance: Number.POSITIVE_INFINITY
    }).aspectRatio;
};

export const getImageFrameFromSource = (
  src: string,
  fallbackWidth: number,
  fallbackHeight: number,
  maxLongSide = DEFAULT_GENERATED_IMAGE_LONG_SIDE
) => new Promise<FrameDimensions>((resolve) => {
  const image = new Image();
  image.onload = () => {
    resolve(fitDimensionsToLongSide(
      image.naturalWidth || fallbackWidth,
      image.naturalHeight || fallbackHeight,
      maxLongSide
    ));
  };
  image.onerror = () => {
    resolve(fitDimensionsToLongSide(fallbackWidth, fallbackHeight, maxLongSide));
  };
  image.src = src;
});

export const centerFrameOnRect = (
  source: FramePosition,
  frame: FrameDimensions
): FramePosition => ({
  x: source.x + source.width / 2 - frame.width / 2,
  y: source.y + source.height / 2 - frame.height / 2,
  width: frame.width,
  height: frame.height
});

export const aspectRatioToGeminiAspectRatio = (aspectRatio: ImageAspectRatio): GeminiAspectRatio | undefined => {
  if (aspectRatio === 'auto') return undefined;
  return aspectRatio;
};
