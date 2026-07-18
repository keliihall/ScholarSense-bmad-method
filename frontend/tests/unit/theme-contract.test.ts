import { describe, expect, it } from 'vitest';

import { themeContract } from '../../src/app/theme/tokens';


describe('UXB-1.0.0 theme contract', () => {
  it('keeps web and mobile primary colors distinct', () => {
    expect(themeContract.colors.web).toEqual({
      primary: '#AF251B',
      hover: '#C53227',
      pressed: '#A7180D',
    });
    expect(themeContract.colors.mobile.primary).toBe('#D03D37');
  });

  it('freezes typography, spacing, radii, breakpoints and targets', () => {
    expect(themeContract.fontFamily).toBe('PingFang SC, SF Pro, Source Han Sans SC, sans-serif');
    expect(themeContract.spacingUnitPx).toBe(4);
    expect(themeContract.radiiPx).toEqual([2, 4, 8]);
    expect(themeContract.breakpoints).toEqual({ mobileMax: 767, tabletMax: 1023, desktopMin: 1024 });
    expect(themeContract.columns).toEqual({ mobile: 4, tablet: 8, desktop: 12 });
    expect(themeContract.targetSizePx).toEqual({ mobilePrimary: 44, desktopControl: 36, desktopAction: 40 });
  });
});
