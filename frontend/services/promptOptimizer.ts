export const restoreMissingReferenceMentions = (
  optimizedPrompt: string,
  originalPrompt: string,
  requiredReferenceMentions: string[]
) => {
  const missingReferences = requiredReferenceMentions.filter(reference => !optimizedPrompt.includes(reference));
  if (missingReferences.length === 0) return optimizedPrompt;

  return [
    optimizedPrompt,
    `必须使用这些参考图标记：${missingReferences.join(' ')}。`,
    `原始用户指令：${originalPrompt}`
  ].join('\n');
};
