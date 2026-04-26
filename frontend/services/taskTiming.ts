export const formatExecutionDuration = (durationMs: number) => {
  const safeDurationMs = Math.max(0, durationMs);
  if (safeDurationMs < 1000) return '不足 1 秒';

  const totalSeconds = Math.floor(safeDurationMs / 1000);
  const seconds = totalSeconds % 60;
  const totalMinutes = Math.floor(totalSeconds / 60);
  const minutes = totalMinutes % 60;
  const hours = Math.floor(totalMinutes / 60);

  if (hours > 0) {
    return `${hours}小时${minutes}分${seconds}秒`;
  }

  if (minutes > 0) {
    return `${minutes}分${seconds}秒`;
  }

  return `${seconds}秒`;
};
