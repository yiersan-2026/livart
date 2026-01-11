import { getApiConfig, hasApiConfig } from './config';

const callGeminiApi = async (contents: any, imageConfig?: any) => {
  if (!hasApiConfig()) {
    throw new Error('请先配置 API 地址和密钥');
  }

  const config = getApiConfig();
  const url = `${config.baseUrl}/v1beta/models/${config.model}:generateContent`;

  const body: any = {
    contents: [{ role: 'user', parts: contents }],
    generationConfig: {
      responseModalities: ['TEXT', 'IMAGE'],
      imageConfig: imageConfig || { aspectRatio: '1:1', imageSize: '1K' }
    }
  };

  const response = await fetch(url, {
    method: 'POST',
    headers: {
      'Content-Type': 'application/json',
      'Accept': 'application/json',
      'Authorization': `Bearer ${config.apiKey}`
    },
    body: JSON.stringify(body)
  });

  if (!response.ok) {
    const error = await response.text();
    throw new Error(`API 请求失败: ${error}`);
  }

  return response.json();
};

const extractImageFromResponse = (data: any): string => {
  if (data.candidates?.[0]?.content?.parts) {
    for (const part of data.candidates[0].content.parts) {
      if (part.inlineData) {
        return `data:image/png;base64,${part.inlineData.data}`;
      }
    }
  }
  throw new Error('未能从响应中获取图像数据');
};

/**
 * 视觉逻辑推理合成：支持无提示词的智能意图推断
 */
export const generateWorkflowImage = async (prompt: string, snapshot: string) => {
  const extractBase64 = (data: string) => data.includes(',') ? data.split(',')[1] : data;

  const finalPrompt = prompt.trim() || "请自动分析快照中的视觉逻辑：如果有箭头指向，执行转换；如果有并列的人和衣服，执行换装（Virtual Try-on）；如果有涂鸦，将其转化为特效。请保持人物面貌特征完全一致，输出高清写实结果。";

  const parts = [
    {
      inline_data: {
        data: extractBase64(snapshot),
        mime_type: "image/png"
      }
    },
    {
      text: `你是一位具备顶级视觉直觉和推理能力的 AI 图像专家。

      任务指令：解析提供的画布快照，并根据其中的"视觉布局"和"用户意图"生成最终的高清图像。

      视觉布局推理指南：
      1. 箭头 (Arrow) = 动作算子。从 A 指向 B 代表将 A 的属性应用到 B 或从 A 转换到 B。
      2. 换装逻辑：如果快照左侧是人物，右侧是单件衣服（或有箭头指向），则自动执行换装。必须严格保持人物的面部、五官、体型和发型，同时完美适配右侧衣服的纹理和剪裁。
      3. 涂鸦逻辑：手绘线条代表特效（如发光、能量场、路径）或需要替换的区域。

      具体指令："${finalPrompt}"

      质量标准：
      - 输出必须是一张干净、写实、高画质的单张图像。
      - 严禁出现任何画布 UI 元素（如选中框、工具栏）。
      - 保持主体特征高度一致。

      请直接返回图像数据流（Base64），不要输出任何解释文字。`
    }
  ];

  const data = await callGeminiApi(parts, { aspectRatio: '1:1', imageSize: '1K' });
  return extractImageFromResponse(data);
};

export const generateImage = async (prompt: string, style: string = 'none') => {
  const parts = [{ text: prompt }];
  const data = await callGeminiApi(parts, { aspectRatio: '1:1', imageSize: '1K' });
  return extractImageFromResponse(data);
};

export const generateBrainstorm = async (topic: string, description: string) => {
  const parts = [{ text: `Brainstorm ideas for: "${topic}". Description: ${description}. Markdown.` }];
  const data = await callGeminiApi(parts);

  if (data.candidates?.[0]?.content?.parts) {
    for (const part of data.candidates[0].content.parts) {
      if (part.text) return part.text;
    }
  }
  throw new Error('未能获取响应');
};
