const getCanvasDimension = (value: number) => Math.max(1, Math.round(value));

const loadImageElement = (src: string) => new Promise<HTMLImageElement>((resolve, reject) => {
  const image = new Image();
  image.onload = () => resolve(image);
  image.onerror = () => reject(new Error('图片加载失败，无法创建编辑蒙版'));
  image.src = src;
});

const dilatePixels = (sourcePixels: Uint8Array, width: number, height: number, radius: number) => {
  if (radius <= 0) return sourcePixels;

  const dilatedPixels = new Uint8Array(sourcePixels);
  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const index = y * width + x;
      if (!sourcePixels[index]) continue;

      for (let dy = -radius; dy <= radius; dy += 1) {
        const nextY = y + dy;
        if (nextY < 0 || nextY >= height) continue;

        for (let dx = -radius; dx <= radius; dx += 1) {
          const nextX = x + dx;
          if (nextX < 0 || nextX >= width) continue;
          if (dx * dx + dy * dy > radius * radius) continue;
          dilatedPixels[nextY * width + nextX] = 1;
        }
      }
    }
  }

  return dilatedPixels;
};

const buildEditableMaskFromPaintPixels = (
  paintPixels: ImageData,
  width: number,
  height: number,
  options: { outlineDilationRadius?: number; editableDilationRadius?: number } = {}
) => {
  const totalPixels = width * height;
  const paintedPixels = new Uint8Array(totalPixels);
  const blockedPixels = new Uint8Array(totalPixels);
  const outlineDilationRadius = options.outlineDilationRadius ?? 2;
  const editableDilationRadius = options.editableDilationRadius ?? 0;
  let hasPaintedArea = false;

  for (let index = 0; index < totalPixels; index += 1) {
    const alpha = paintPixels.data[index * 4 + 3];
    if (alpha > 12) {
      paintedPixels[index] = 1;
      blockedPixels[index] = 1;
      hasPaintedArea = true;
    }
  }

  if (!hasPaintedArea) {
    return { editablePixels: paintedPixels, hasPaintedArea: false };
  }

  for (let y = 0; y < height; y += 1) {
    for (let x = 0; x < width; x += 1) {
      const index = y * width + x;
      if (!paintedPixels[index]) continue;

      for (let dy = -outlineDilationRadius; dy <= outlineDilationRadius; dy += 1) {
        const nextY = y + dy;
        if (nextY < 0 || nextY >= height) continue;

        for (let dx = -outlineDilationRadius; dx <= outlineDilationRadius; dx += 1) {
          const nextX = x + dx;
          if (nextX < 0 || nextX >= width) continue;
          blockedPixels[nextY * width + nextX] = 1;
        }
      }
    }
  }

  const outsidePixels = new Uint8Array(totalPixels);
  const queue = new Int32Array(totalPixels);
  let queueStart = 0;
  let queueEnd = 0;

  const enqueueOutside = (index: number) => {
    if (index < 0 || index >= totalPixels || blockedPixels[index] || outsidePixels[index]) return;
    outsidePixels[index] = 1;
    queue[queueEnd] = index;
    queueEnd += 1;
  };

  for (let x = 0; x < width; x += 1) {
    enqueueOutside(x);
    enqueueOutside((height - 1) * width + x);
  }
  for (let y = 0; y < height; y += 1) {
    enqueueOutside(y * width);
    enqueueOutside(y * width + width - 1);
  }

  while (queueStart < queueEnd) {
    const index = queue[queueStart];
    queueStart += 1;
    const x = index % width;
    const y = Math.floor(index / width);

    if (x > 0) enqueueOutside(index - 1);
    if (x + 1 < width) enqueueOutside(index + 1);
    if (y > 0) enqueueOutside(index - width);
    if (y + 1 < height) enqueueOutside(index + width);
  }

  const editablePixels = new Uint8Array(totalPixels);
  for (let index = 0; index < totalPixels; index += 1) {
    editablePixels[index] = paintedPixels[index] || (!blockedPixels[index] && !outsidePixels[index]) ? 1 : 0;
  }

  return {
    editablePixels: dilatePixels(editablePixels, width, height, editableDilationRadius),
    hasPaintedArea: true
  };
};

export const createTransparentEditMask = async (
  paintMaskDataUrl: string,
  imageDataUrl: string,
  displayWidth: number,
  displayHeight: number,
  options: { outlineDilationRadius?: number; editableDilationRadius?: number } = {}
) => {
  const displayCanvasWidth = getCanvasDimension(displayWidth);
  const displayCanvasHeight = getCanvasDimension(displayHeight);
  const image = await loadImageElement(imageDataUrl);
  const imageWidth = getCanvasDimension(image.naturalWidth || displayCanvasWidth);
  const imageHeight = getCanvasDimension(image.naturalHeight || displayCanvasHeight);
  const paintImage = await loadImageElement(paintMaskDataUrl);

  const displayPaintCanvas = document.createElement('canvas');
  displayPaintCanvas.width = displayCanvasWidth;
  displayPaintCanvas.height = displayCanvasHeight;
  const displayPaintContext = displayPaintCanvas.getContext('2d');
  if (!displayPaintContext) throw new Error('无法创建编辑蒙版');
  displayPaintContext.clearRect(0, 0, displayCanvasWidth, displayCanvasHeight);
  displayPaintContext.drawImage(paintImage, 0, 0, displayCanvasWidth, displayCanvasHeight);

  const normalizedPaintCanvas = document.createElement('canvas');
  normalizedPaintCanvas.width = imageWidth;
  normalizedPaintCanvas.height = imageHeight;
  const normalizedPaintContext = normalizedPaintCanvas.getContext('2d');
  if (!normalizedPaintContext) throw new Error('无法创建编辑蒙版');

  const containScale = Math.min(displayCanvasWidth / imageWidth, displayCanvasHeight / imageHeight);
  const renderedWidth = imageWidth * containScale;
  const renderedHeight = imageHeight * containScale;
  const renderedX = (displayCanvasWidth - renderedWidth) / 2;
  const renderedY = (displayCanvasHeight - renderedHeight) / 2;

  normalizedPaintContext.clearRect(0, 0, imageWidth, imageHeight);
  normalizedPaintContext.drawImage(
    displayPaintCanvas,
    renderedX,
    renderedY,
    renderedWidth,
    renderedHeight,
    0,
    0,
    imageWidth,
    imageHeight
  );

  const paintPixels = normalizedPaintContext.getImageData(0, 0, imageWidth, imageHeight);
  const { editablePixels, hasPaintedArea } = buildEditableMaskFromPaintPixels(
    paintPixels,
    imageWidth,
    imageHeight,
    options
  );
  if (!hasPaintedArea) return null;

  const apiMaskCanvas = document.createElement('canvas');
  apiMaskCanvas.width = imageWidth;
  apiMaskCanvas.height = imageHeight;
  const apiMaskContext = apiMaskCanvas.getContext('2d');
  if (!apiMaskContext) throw new Error('无法创建编辑蒙版');

  const apiMaskPixels = apiMaskContext.createImageData(imageWidth, imageHeight);
  for (let pixelIndex = 0; pixelIndex < editablePixels.length; pixelIndex += 1) {
    const outputIndex = pixelIndex * 4;
    const isEditable = editablePixels[pixelIndex] === 1;
    apiMaskPixels.data[outputIndex] = 0;
    apiMaskPixels.data[outputIndex + 1] = 0;
    apiMaskPixels.data[outputIndex + 2] = 0;
    apiMaskPixels.data[outputIndex + 3] = isEditable ? 0 : 255;
  }

  apiMaskContext.putImageData(apiMaskPixels, 0, 0);
  return apiMaskCanvas.toDataURL('image/png');
};
