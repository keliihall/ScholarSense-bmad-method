import { defineConfig, devices } from '@playwright/test';

const approvedBrowserExecutable = process.env.SCHOLARSENSE_TEST_BROWSER_EXECUTABLE;

export default defineConfig({
  testDir: './tests/baseline',
  fullyParallel: false,
  retries: 0,
  reporter: 'line',
  use: {
    baseURL: 'http://127.0.0.1:4173/scholarsense/',
    browserName: 'chromium',
    colorScheme: 'light',
    locale: 'zh-CN',
    serviceWorkers: 'block',
    trace: 'retain-on-failure',
    ...(approvedBrowserExecutable === undefined
      ? {}
      : { launchOptions: { executablePath: approvedBrowserExecutable } }),
  },
  projects: [
    { name: 'desktop-reference', use: { ...devices['Desktop Chrome'], viewport: { width: 1440, height: 900 } } },
    { name: 'desktop-minimum', use: { ...devices['Desktop Chrome'], viewport: { width: 1366, height: 768 } } },
    { name: 'responsive-768', use: { viewport: { width: 768, height: 1024 }, deviceScaleFactor: 1 } },
    { name: 'responsive-375', use: { viewport: { width: 375, height: 812 }, deviceScaleFactor: 1, isMobile: true, hasTouch: true } },
  ],
  webServer: [
    {
      command: 'npm run preview -- --host 127.0.0.1 --port 4173 --strictPort',
      url: 'http://127.0.0.1:4173/scholarsense/',
      reuseExistingServer: false,
    },
    {
      command: 'python3 -m http.server 4174 --bind 0.0.0.0 --directory tests/host-fixture',
      url: 'http://127.0.0.1:4174/',
      reuseExistingServer: false,
    },
  ],
});
