/** UXB-1.0.0 theme token extension point. */
export type ThemeTokenContribution = Readonly<Record<string, string>>;

export const themeTokenContributions: readonly ThemeTokenContribution[] = Object.freeze([]);

export { themeContract } from './tokens';
