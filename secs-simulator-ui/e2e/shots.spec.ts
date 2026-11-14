import { test, type Page } from '@playwright/test';
import { mockApi } from './fixtures';

/**
 * 视觉取样:把每个页面在浅色/深色、桌面/窄屏下各截一张。
 *
 * <p>只在设置 SHOTS=1 时运行(npm run test:e2e 不跑它),
 * 用于重构前后对比——不是断言,是"眼睛"。</p>
 */

const VIEWS = [
  { name: 'workbench', hash: '#/workbench' },
  { name: 'device-plans', hash: '#/device/line1/runtime/plans' },
  { name: 'device-catalog', hash: '#/device/line1/runtime/catalog' },
  { name: 'device-svids', hash: '#/device/line1/runtime/svids' },
  { name: 'device-manual', hash: '#/device/line1/runtime/manual' },
  { name: 'device-overview', hash: '#/device/line1/config/overview' },
  { name: 'device-connection', hash: '#/device/line1/config/connection' },
  { name: 'device-structure', hash: '#/device/line1/config/structure' },
  { name: 'device-document', hash: '#/device/line1/config/document' },
  { name: 'device-constants', hash: '#/device/line1/config/constants' },
  { name: 'device-revisions', hash: '#/device/line1/config/revisions' },
  { name: 'hosts', hash: '#/hosts' },
  { name: 'host-overview', hash: '#/hosts/host-1/config/overview' },
  { name: 'host-trace', hash: '#/hosts/host-1/runtime/trace' },
  { name: 'host-collect', hash: '#/hosts/host-1/runtime/collect' },
  { name: 'host-presets', hash: '#/hosts/host-1/config/presets' },
  { name: 'host-templates', hash: '#/host-templates' },
  { name: 'templates', hash: '#/templates' },
  { name: 'audit', hash: '#/system/audit' },
] as const;

test.skip(!process.env.SHOTS, '视觉取样只在 SHOTS=1 时运行');

test.beforeEach(async ({ page }) => {
  await mockApi(page);
});

async function shot(page: Page, file: string): Promise<void> {
  await page.waitForTimeout(450);
  await page.screenshot({ path: `shots/redesign/${file}.png`, fullPage: false });
}

for (const theme of ['light', 'dark'] as const) {
  test.describe(`${theme}`, () => {
    test.use({ viewport: { width: 1440, height: 900 } });

    for (const view of VIEWS) {
      test(`${theme} ${view.name}`, async ({ page }) => {
        await page.addInitScript((value) => {
          localStorage.setItem('secs-sim-ui:preferences', JSON.stringify({ theme: value }));
        }, theme);
        await page.goto(`/${view.hash}`);
        await shot(page, `${theme}-${view.name}`);
      });
    }

    test(`${theme} sidebar-resized`, async ({ page }) => {
      await page.addInitScript((value) => {
        localStorage.setItem('secs-sim-ui:preferences', JSON.stringify({ theme: value }));
      }, theme);
      await page.goto('/#/workbench');
      await page.waitForTimeout(300);
      /* 拖动侧栏右缘 +90px，验证"左右推动展示"确实生效 */
      const handle = page.locator('.sidenav-resizer');
      const box = await handle.boundingBox();
      if (box !== null) {
        await page.mouse.move(box.x + box.width / 2, box.y + 200);
        await page.mouse.down();
        await page.mouse.move(box.x + box.width / 2 + 90, box.y + 200, { steps: 8 });
        await page.mouse.up();
      }
      await shot(page, `${theme}-sidebar-wide`);
    });

    test(`${theme} monitor-drawer`, async ({ page }) => {
      await page.addInitScript((value) => {
        localStorage.setItem('secs-sim-ui:preferences', JSON.stringify({ theme: value }));
      }, theme);
      await page.goto('/#/device/line1/runtime/plans');
      await page.locator('.monitor-toggle').click();
      await shot(page, `${theme}-monitor-drawer`);
    });

    test(`${theme} command-palette`, async ({ page }) => {
      await page.addInitScript((value) => {
        localStorage.setItem('secs-sim-ui:preferences', JSON.stringify({ theme: value }));
      }, theme);
      await page.goto('/#/workbench');
      await page.waitForTimeout(300);
      await page.keyboard.press('Control+k');
      await shot(page, `${theme}-palette`);
    });
  });
}

test.describe('mobile', () => {
  test.use({ viewport: { width: 390, height: 844 } });

  for (const view of ['workbench', 'device-catalog', 'device-svids', 'hosts'] as const) {
    const target = VIEWS.find((item) => item.name === view);
    test(`mobile ${view}`, async ({ page }) => {
      await page.goto(`/${target?.hash}`);
      await shot(page, `mobile-${view}`);
    });
  }
});
