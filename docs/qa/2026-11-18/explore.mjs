// 探索脚本：遍历所有页面路由，截图 + 枚举每个页面的按钮/可点击元素
// 用真实后端 http://127.0.0.1:5000，同源托管 dist
import { createRequire } from 'node:module';
const require = createRequire('file:///E:/ai/secs-simulator/secs-simulator-ui/');
const { chromium } = require('playwright');
import { writeFileSync, mkdirSync } from 'node:fs';

const BASE = 'http://127.0.0.1:5000';
const OUT = 'E:/ai/secs-simulator/docs/qa/2026-11-18';
const SHOTS = OUT + '/screenshots';
mkdirSync(SHOTS, { recursive: true });

const HOST_ID = 'host_01M7JGP6SCX1NPR0VXGJXA34RT';

// 页面矩阵：name -> hash
const PAGES = [
  ['01-workbench', '#/workbench'],
  ['02-device-config-overview', '#/device/ETCH01/config/overview'],
  ['03-device-config-connection', '#/device/ETCH01/config/connection'],
  ['04-device-config-structure', '#/device/ETCH01/config/structure'],
  ['05-device-config-svids', '#/device/ETCH01/config/svids'],
  ['06-device-config-context', '#/device/ETCH01/config/context'],
  ['07-device-config-events', '#/device/ETCH01/config/events'],
  ['08-device-config-cycle', '#/device/ETCH01/config/cycle'],
  ['09-device-config-plans', '#/device/ETCH01/config/plans'],
  ['10-device-config-document', '#/device/ETCH01/config/document'],
  ['11-device-config-constants', '#/device/ETCH01/config/constants'],
  ['12-device-config-revisions', '#/device/ETCH01/config/revisions'],
  ['13-device-runtime-plans', '#/device/ETCH01/runtime/plans'],
  ['14-device-runtime-cycle', '#/device/ETCH01/runtime/cycle'],
  ['15-device-runtime-manual', '#/device/ETCH01/runtime/manual'],
  ['16-host-list', '#/hosts'],
  [`17-host-config-overview`, `#/hosts/${HOST_ID}/config/overview`],
  [`18-host-config-presets`, `#/hosts/${HOST_ID}/config/presets`],
  [`19-host-config-trace`, `#/hosts/${HOST_ID}/config/trace`],
  [`20-host-config-collect`, `#/hosts/${HOST_ID}/config/collect`],
  [`21-host-runtime-messages`, `#/hosts/${HOST_ID}/runtime/messages`],
  [`22-host-runtime-trace`, `#/hosts/${HOST_ID}/runtime/trace`],
  [`23-host-runtime-collect`, `#/hosts/${HOST_ID}/runtime/collect`],
  ['24-host-templates', '#/host-templates'],
  ['25-device-templates', '#/templates'],
  ['26-param-templates', '#/svid-templates'],
  ['27-audit', '#/system/audit'],
];

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 }, deviceScaleFactor: 1 });
const page = await ctx.newPage();

const consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message));

const report = [];
for (const [name, hash] of PAGES) {
  consoleErrors.length = 0;
  try {
    await page.goto(BASE + '/' + hash, { waitUntil: 'domcontentloaded', timeout: 20000 });
    await page.waitForTimeout(900); // 等 API 数据渲染
    // 枚举按钮
    const buttons = await page.evaluate(() => {
      const out = [];
      document.querySelectorAll('main button, .app-tabbar button, .titlebar button').forEach((b, i) => {
        const r = b.getBoundingClientRect();
        if (r.width === 0 || r.height === 0) return;
        const text = (b.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 40);
        const title = b.getAttribute('title') || '';
        const aria = b.getAttribute('aria-label') || '';
        const cls = b.className || '';
        out.push({ i, text, title, aria, cls, disabled: b.disabled });
      });
      return out;
    });
    const panelIds = await page.evaluate(() =>
      [...document.querySelectorAll('main [id^="panel-"]')].map((e) => e.id));
    await page.screenshot({ path: `${SHOTS}/${name}.png`, fullPage: true });
    report.push({ name, hash, buttons, panelIds, consoleErrors: [...consoleErrors] });
    console.log(`OK  ${name}  buttons=${buttons.length} panels=${panelIds.length} errs=${consoleErrors.length}`);
  } catch (e) {
    console.log(`ERR ${name}  ${e.message}`);
    report.push({ name, hash, error: e.message, consoleErrors: [...consoleErrors] });
  }
}

writeFileSync(OUT + '/explore-report.json', JSON.stringify(report, null, 2));
console.log('\nDONE -> explore-report.json');
await browser.close();
