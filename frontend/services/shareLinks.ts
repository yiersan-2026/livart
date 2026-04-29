export const LIVART_SHARE_PROMOTION_TEXT = '进入 https://livart.suntools.pro/ 使用最强生图模型 GPT-image-2 创作图片，释放创造力，无任何收费！';

export type ImageSharePageParams = {
  imageUrl: string;
  title: string;
  text: string;
};

export const buildLivartImageSharePageUrl = (params: Pick<ImageSharePageParams, 'imageUrl' | 'title'>) => {
  const pageUrl = new URL('/share', window.location.origin);
  pageUrl.searchParams.set('image', params.imageUrl);
  pageUrl.searchParams.set('title', params.title);
  pageUrl.searchParams.set('text', LIVART_SHARE_PROMOTION_TEXT);
  return pageUrl.toString();
};

export const parseLivartImageSharePageParams = (search = window.location.search): ImageSharePageParams => {
  const params = new URLSearchParams(search);
  const imageUrl = params.get('image') || '';
  const title = params.get('title') || 'livart 图片';
  const text = params.get('text') || LIVART_SHARE_PROMOTION_TEXT;

  return { imageUrl, title, text };
};
