
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
  source?: 'ai' | 'upload' | 'external' | 'crop';
  assetId?: string;
  previewContent?: string;
  thumbnailContent?: string;
  x: number;
  y: number;
  width: number;
  height: number;
  status: 'pending' | 'loading' | 'completed' | 'error';
  label?: string;
  zIndex?: number;
  parentId?: string;
  prompt?: string;
  originalPrompt?: string;
  optimizedPrompt?: string;
  layerGroupId?: string;
  layerRole?: 'subject' | 'background';
  textStyle?: CanvasTextStyle;
  imageJobId?: string;
  imageJobStartedAt?: number;
  groundingUrls?: string[];
  // 增强型 Workflow 字段
  layers: CompositionLayer[];
  drawingData?: string; // 涂鸦层的 base64 数据
  maskData?: string; // 旧版共享蒙版草稿，保留用于兼容历史项目
  redrawMaskData?: string; // 图片局部重绘蒙版草稿
  removerMaskData?: string; // 删除物体蒙版草稿
  compositeImage?: string; // 最终渲染的快照
}

export interface CanvasTextStyle {
  fontFamily?: string;
  fontSize?: number;
  fontWeight?: number;
  fontStyle?: 'normal' | 'italic';
  textDecoration?: 'none' | 'underline' | 'line-through';
  color?: string;
  strokeColor?: string;
  strokeWidth?: number;
  backgroundColor?: string;
  textAlign?: 'left' | 'center' | 'right';
  lineHeight?: number;
}

export type DesignStyle = 'none' | 'cyberpunk' | 'minimalist' | '3d-clay' | 'watercolor' | 'sketch';

export type ImageAspectRatio = 'auto' | '1:1' | '4:3' | '3:4' | '16:9' | '9:16';

export type CanvasTool = 'select' | 'pan' | 'text';

export interface AgentPlanStep {
  id: string;
  title: string;
  description: string;
  type: 'analysis' | 'prompt' | 'generate' | 'edit';
  status: 'pending' | 'running' | 'completed' | 'error';
}

export interface AgentPlan {
  allowed: boolean;
  responseMode: 'execute' | 'answer' | 'reject';
  rejectionMessage?: string;
  answerMessage?: string;
  taskType: 'text-to-image' | 'image-edit';
  mode: 'generate' | 'edit' | 'background-removal' | 'remover' | 'layer-subject' | 'layer-background' | 'view-change';
  count: number;
  baseImageId?: string;
  referenceImageIds: string[];
  aspectRatio: ImageAspectRatio;
  summary: string;
  displayTitle: string;
  displayMessage: string;
  thinkingSteps: string[];
  steps: AgentPlanStep[];
  source: 'ai' | 'fallback';
}

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
  imageIds?: string[];
  imageResultCards?: ChatImageResultCard[];
  durationMs?: number;
  agentPlan?: AgentPlan;
  agentRunId?: string;
  agentRunStatus?: 'running' | 'waiting-reconnect' | 'completed' | 'error';
}

export interface ChatImageResultCard {
  imageId: string;
  modelName?: string;
  title?: string;
  description?: string;
}
