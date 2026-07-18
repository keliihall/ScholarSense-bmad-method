// 通用格式化工具

/** 距截止时间的剩余/超期描述 */
export function deadlineHint(deadline: string): { text: string; overdue: boolean } {
  const end = new Date(deadline.replace(/-/g, '/')).getTime();
  const now = new Date('2026/06/24 10:00').getTime(); // 原型固定「当前时间」，保证演示稳定
  const diffH = Math.round((end - now) / 3600000);
  if (diffH < 0) return { text: `已超期 ${Math.abs(diffH)}h`, overdue: true };
  if (diffH < 24) return { text: `剩余 ${diffH}h`, overdue: false };
  return { text: `剩余 ${Math.round(diffH / 24)}天`, overdue: false };
}

/** 百分比文案 */
export function pct(v: number): string {
  return `${v.toFixed(1)}%`;
}

/** 评分 → 优先级文案 */
export function priorityOf(score: number): string {
  if (score >= 85) return '极高';
  if (score >= 70) return '高';
  if (score >= 50) return '中';
  return '低';
}
