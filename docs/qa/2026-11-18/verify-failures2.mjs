// 用独立页面分别验证每个场景，避免崩溃污染
import { createRequire } from 'node:module';
const require = createRequire('file:///E:/ai/secs-simulator/secs-simulator-ui/');
const { chromium } = require('playwright');
const BASE = 'http://127.0.0.1:5000';
const OUT = 'E:/ai/secs-simulator/docs/qa/2026-11-18';

const browser = await chromium.launch({ headless: true });
const findings = [];

async function freshVisit(hash, label, shot) {
  const ctx = await browser.newContext({ viewport: { width: 1400, height: 1000 } });
  const page = await ctx.newPage();
  const errs = [];
  page.on('console', (m) => { if (m.type() === 'error') errs.push(m.text().slice(0, 120)); });
  page.on('pageerror', (e) => errs.push('PAGEERROR ' + e.message.slice(0, 120)));
  await page.goto(BASE + '/' + hash, { waitUntil: 'domcontentloaded' });
  await page.waitForTimeout(1300);
  const mainText = (await page.locator('main').innerText().catch(() => '')).replace(/\s+/g, ' ');
  const ids = await page.evaluate(() => [...document.querySelectorAll('main [id^="panel-"]')].map((e) => e.id));
  const btnCount = await page.locator('main button').count();
  await page.screenshot({ path: `${OUT}/screenshots/${shot}`, fullPage: false }).catch(() => {});
  const r = { label, hash, mainLen: mainText.length, textHead: mainText.slice(0, 200), ids, btnCount, errs };
  findings.push(r);
  console.log(`\n[${label}] mainLen=${mainText.length} btns=${btnCount} errs=${errs.length}`);
  console.log('  text:', mainText.slice(0, 180) || '(EMPTY)');
  console.log('  ids:', ids.join(','));
  if (errs.length) console.log('  ERR:', errs[0]);
  await ctx.close();
  return page;
}

// 场景1：不存在的设备（多种 zone/page）
await freshVisit('#/device/ghost/runtime/plans', 'ghost设备', 'verify-ghost-device.png');
await freshVisit('#/device/ghost/config/overview', 'ghost设备配置区', 'verify-ghost-config.png');

// 场景2：连接页字段+校验（真实设备）
const p2 = await freshVisit('#/device/ETCH01/config/connection', '连接页', 'verify-connection.png');
const connPanel = p2.locator('#panel-connection');
if (await connPanel.count() > 0) {
  console.log('  connection inputs:', await connPanel.locator('input,select').count());
  const vb = connPanel.getByRole('button', { name: '校验' });
  if (await vb.count() > 0) {
    await vb.click(); await p2.waitForTimeout(1500);
    const toasts = await p2.locator('.toast-item, [class*="toast"]').allInnerTexts().catch(() => []);
    console.log('  校验toast:', toasts.join('|').replace(/\s+/g, ' ').slice(0, 150) || '(none)');
  }
}
await p2.context().close();

// 场景3：工艺周期
await freshVisit('#/device/ETCH01/config/cycle', '工艺周期', 'verify-cycle.png');

console.log('\n=== JSON ===');
require('node:fs').writeFileSync(`${OUT}/verify-failures2.json`, JSON.stringify(findings, null, 2));
await browser.close();
