// 脱敏工具：体现 PIPL「最小必要 + 授权可见」。未授权时返回掩码。

/** 手机号脱敏：138****8000 */
export function maskPhone(phone: string, allow: boolean): string {
  if (allow) return phone;
  return phone.replace(/(\d{3})\d{4}(\d{4})/, '$1****$2');
}

/** 学号脱敏：保留前 2 后 2 */
export function maskId(id: string, allow: boolean): string {
  if (allow) return id;
  if (id.length <= 4) return '****';
  return `${id.slice(0, 2)}****${id.slice(-2)}`;
}

/** 高敏标签（如心理关注）未授权时整体隐藏 */
export function maskSensitiveText(text: string, allow: boolean): string {
  return allow ? text : '••• 授权可见';
}
