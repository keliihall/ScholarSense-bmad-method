export const themeContract = Object.freeze({
  colors: {
    web: { primary: '#AF251B', hover: '#C53227', pressed: '#A7180D' },
    mobile: { primary: '#D03D37' },
  },
  fontFamily: 'PingFang SC, SF Pro, Source Han Sans SC, sans-serif',
  spacingUnitPx: 4,
  radiiPx: [2, 4, 8] as const,
  breakpoints: { mobileMax: 767, tabletMax: 1023, desktopMin: 1024 },
  columns: { mobile: 4, tablet: 8, desktop: 12 },
  targetSizePx: { mobilePrimary: 44, desktopControl: 36, desktopAction: 40 },
});
