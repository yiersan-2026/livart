
export interface CompositionLayer {
  id: string;
  type: 'image' | 'text';
  content: string; // base64 or text string
  x: number;
  y: number;
  width: number;
  height: number;
  rotation: number;
}

export interface CanvasItem {
  id: string;
  type: 'image' | 'text' | 'research' | 'workflow';
  content: string;
  x: number;
  y: number;
  width: number;
  height: number;
  status: 'pending' | 'loading' | 'completed' | 'error';
  label?: string;
  zIndex?: number;
  parentId?: string;
  prompt?: string;
  imageJobId?: string;
  groundingUrls?: string[];
  // 增强型 Workflow 字段
  layers: CompositionLayer[];
  drawingData?: string; // 涂鸦层的 base64 数据
  maskData?: string; // 图片局部重绘蒙版草稿
  compositeImage?: string; // 最终渲染的快照
}

export type DesignStyle = 'none' | 'cyberpunk' | 'minimalist' | '3d-clay' | 'watercolor' | 'sketch';

export type ImageAspectRatio = 'auto' | '1:1' | '4:3' | '3:4' | '16:9' | '9:16';

export interface PlanStep {
  id: string;
  title: string;
  description: string;
  status: 'pending' | 'running' | 'completed' | 'error';
  type: 'generate_image' | 'brainstorm' | 'research' | 'workflow';
  imagePrompt?: string;
}

export interface ChatMessage {
  id: string;
  role: 'user' | 'assistant';
  text: string;
  timestamp: number;
}
