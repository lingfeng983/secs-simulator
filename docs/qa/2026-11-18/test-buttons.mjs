// 逐按钮功能测试（高效版）：每个页面加载一次，顺序点击所有按钮，采集反馈并截图。
// 破坏性按钮点开确认框后取消；导航导致跳转时回到本页重载。
import { createRequire } from 'node:module';
import { writeFileSync, mkdirSync } from 'node:fs';
const require = createRequire('file:///E:/ai/secs-simulator/secs-simulator-ui/');
const { chromium } = require('playwright');

const BASE = 'http://127.0.0.1:5000';
const OUT = 'E:/ai/secs-simulator/docs/qa/2026-11-18';
const SHOTS = OUT + '/screenshots/buttons';
mkdirSync(SHOTS, { recursive: true });

const HOST_ID = 'host_01M7JGP6SCX1NPR0VXGJXA34RT';
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
  ['17-host-config-overview', `#/hosts/${HOST_ID}/config/overview`],
  ['18-host-config-presets', `#/hosts/${HOST_ID}/config/presets`],
  ['19-host-config-trace', `#/hosts/${HOST_ID}/config/trace`],
  ['20-host-config-collect', `#/hosts/${HOST_ID}/config/collect`],
  ['21-host-runtime-messages', `#/hosts/${HOST_ID}/runtime/messages`],
  ['22-host-runtime-trace', `#/hosts/${HOST_ID}/runtime/trace`],
  ['23-host-runtime-collect', `#/hosts/${HOST_ID}/runtime/collect`],
  ['24-host-templates', '#/host-templates'],
  ['25-device-templates', '#/templates'],
  ['26-param-templates', '#/svid-templates'],
  ['27-audit', '#/system/audit'],
];

const browser = await chromium.launch({ headless: true });
const ctx = await browser.newContext({ viewport: { width: 1500, height: 1000 }, acceptDownloads: true });
const page = await ctx.newPage();
let consoleErrors = [];
page.on('console', (m) => { if (m.type() === 'error') consoleErrors.push(m.text()); });
page.on('pageerror', (e) => consoleErrors.push('PAGEERROR: ' + e.message));

const results = [];

// 给当前页所有可见按钮打稳定 data 标记，返回清单
async function markButtons() {
  return await page.evaluate(() => {
    const out = [];
    document.querySelectorAll('main button, .titlebar button').forEach((b, i) => {
      b.setAttribute('data-qa', String(i));
      const r = b.getBoundingClientRect();
      if (r.width === 0 || r.height === 0) return;
      const text = (b.innerText || '').trim().replace(/\s+/g, ' ').slice(0, 40);
      const aria = b.getAttribute('aria-label') || '';
      const title = b.getAttribute('title') || '';
      const label = text || aria || title || `(btn#${i})`;
      out.push({ i, label, text, aria, title, disabled: b.disabled });
    });
    return out;
  });
}

async function loadPage(hash) {
  await page.goto(BASE + '/' + hash, { waitUntil: 'domcontentloaded', timeout: 20000 });
  await page.waitForTimeout(400);
  return markButtons();
}

async function closeOverlays() {
  const dlg = page.locator('[role="dialog"], .modal').filter({ visible: true }).first();
  if (await dlg.count() > 0) {
    const cancel = dlg.locator('button').filter({ hasText: /取消|关闭|返回|^×$/ }).first();
    if (await cancel.count() > 0 && await cancel.isVisible().catch(() => false)) {
      await cancel.click({ timeout: 800 }).catch(() => {});
    } else {
      await page.keyboard.press('Escape').catch(() => {});
    }
    await page.keyboard.press('Escape').catch(() => {});
  }
  // 关闭命令面板/快捷键/抽屉等浮层
  await page.keyboard.press('Escape').catch(() => {});
}

for (const [pname, hash] of PAGES) {
  console.log(`\n===== PAGE ${pname} =====`);
  let buttons;
  try { buttons = await loadPage(hash); }
  catch (e) { console.log('  load failed: ' + e.message); continue; }

  for (const btn of buttons) {
    const rec = { page: pname, hash, ...btn, result: '', feedback: '', shot: '', error: '' };
    if (btn.disabled) { rec.result = 'disabled'; results.push(rec); continue; }
    try {
      consoleErrors = [];
      const beforeHash = await page.evaluate(() => location.hash);
      const loc = page.locator(`button[data-qa="${btn.i}"]`);
      if (await loc.count() === 0) { rec.result = 'not-found'; results.push(rec); continue; }

      await loc.click({ timeout: 2500, force: true });
      await page.waitForTimeout(200);

      const feedback = [];
      const dialog = page.locator('[role="dialog"], .modal').filter({ visible: true }).first();
      if (await dialog.count() > 0) {
        const dtext = (await dialog.innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 110);
        feedback.push('弹窗:' + dtext);
      }
      const toast = page.locator('.toast-item, .toast, [class*="toast-"]').filter({ visible: true }).first();
      if (await toast.count() > 0) {
        feedback.push('提示:' + (await toast.innerText().catch(() => '')).replace(/\s+/g, ' ').slice(0, 90));
      }
      const afterHash = await page.evaluate(() => location.hash);
      if (afterHash !== beforeHash) feedback.push('跳转:' + afterHash);
      // 检测 DOM 按钮数量变化（新增行/删除行）
      const newBtnCount = await page.evaluate(() => document.querySelectorAll('main button, .titlebar button').length);

      rec.feedback = feedback.join(' | ');
      rec.result = feedback.length > 0 ? 'responded' : 'clicked';
      if (consoleErrors.length > 0) { rec.error = consoleErrors.join(' || '); rec.result += ' CONSOLE-ERROR'; }

      if (feedback.length > 0 || consoleErrors.length > 0) {
        const safe = pname.replace(/[^\w-]/g, '_');
        const shot = `${SHOTS}/${safe}_b${String(btn.i).padStart(3, '0')}.png`;
        await page.screenshot({ path: shot, fullPage: false }).catch(() => {});
        rec.shot = shot.replace(OUT + '/', '');
      }
      results.push(rec);
      console.log(`  b${String(btn.i).padStart(3)} ${btn.label.slice(0, 20).padEnd(20)} -> ${rec.result}`);

      // 善后：关闭弹窗
      await closeOverlays();
      // 跳转或 DOM 大变则需重载恢复本页
      if (afterHash !== beforeHash) {
        await page.goto(BASE + '/' + hash, { waitUntil: 'domcontentloaded' }).catch(() => {});
        await page.waitForTimeout(300);
        buttons = await markButtons();
      } else if (Math.abs(newBtnCount - buttons.length) > 3) {
        await page.goto(BASE + '/' + hash, { waitUntil: 'domcontentloaded' }).catch(() => {});
        await page.waitForTimeout(300);
        buttons = await markButtons();
      }
    } catch (e) {
      rec.result = 'ERROR';
      rec.error = e.message.slice(0, 200);
      results.push(rec);
      console.log(`  b${String(btn.i).padStart(3)} ${btn.label.slice(0, 20).padEnd(20)} -> ERROR ${e.message.slice(0, 40)}`);
      await closeOverlays().catch(() => {});
      // 出错后重载恢复
      await page.goto(BASE + '/' + hash, { waitUntil: 'domcontentloaded' }).catch(() => {});
      await page.waitForTimeout(300);
      buttons = await markButtons();
    }
  }
}

writeFileSync(OUT + '/button-test-report.json', JSON.stringify(results, null, 2));
console.log(`\nDONE. total=${results.length}`);
console.log('with console errors: ' + results.filter((r) => r.error).length);
await browser.close();
