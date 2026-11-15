/**
 * 打包后端端到端验收：新建设备 → 新建主机 → 连接设备 → S1F3 数据采集 → S2F23/S6F1 Trace 采集。
 *
 * <p>默认跑打包进安装包的那份 secs-sim.exe（secs-simulator-gui/src-tauri/resources），
 * 用全新临时工作目录，全程只走 HTTP API（与界面走的是同一组端点），跑完自己收尾。
 * 「装机后点得动、数采得回来」这类问题（例如原生镜像漏了 seed 资源导致后端起不来）
 * 用一条命令就能复验。</p>
 *
 * 用法:
 *   node scripts/verify-trace-flow.mjs              # 测打包的那个 exe
 *   node scripts/verify-trace-flow.mjs <exe 路径>    # 测别的后端（如 secs-cli/target/secs-sim.exe）
 */

import { spawn } from 'node:child_process';
import { existsSync, mkdtempSync, rmSync } from 'node:fs';
import { tmpdir } from 'node:os';
import { dirname, join, resolve } from 'node:path';
import { fileURLToPath } from 'node:url';

const REPO = resolve(dirname(fileURLToPath(import.meta.url)), '..');
const DEFAULT_EXE = join(REPO, 'secs-simulator-gui', 'src-tauri', 'resources', 'secs-sim.exe');
const EXE = process.argv[2] ?? process.env.SECS_EXE ?? DEFAULT_EXE;
const WORK = mkdtempSync(join(tmpdir(), 'secs-e2e-'));

if (!existsSync(EXE)) {
  console.error(`找不到后端程序: ${EXE}`);
  console.error('先跑 scripts/package.ps1 -Target Windows（会顺带把 exe 拷进 src-tauri/resources），或显式传路径。');
  process.exit(1);
}


const steps = [];
let failures = 0;
/** 后端句柄：main 里创建，finally 里统一收尾。 */
let backendHandle = null;

function log(line = '') {
  console.log(line);
}

function record(name, ok, detail = '') {
  steps.push({ name, ok, detail });
  if (!ok) {
    failures += 1;
  }
  log(`${ok ? 'PASS' : 'FAIL'}  ${name}${detail === '' ? '' : `  —  ${detail}`}`);
}

const sleep = (ms) => new Promise((resolve) => setTimeout(resolve, ms));

async function api(base, path, init) {
  const response = await fetch(`${base}${path}`, init);
  const text = await response.text();
  let body = null;
  try {
    body = text === '' ? null : JSON.parse(text);
  } catch {
    body = text;
  }
  return { status: response.status, ok: response.ok, body, text };
}

function post(base, path, payload) {
  return api(base, path, {
    method: 'POST',
    headers: { 'Content-Type': 'application/json; charset=utf-8' },
    body: payload === undefined ? undefined : JSON.stringify(payload),
  });
}

async function waitFor(label, probe, timeoutMs = 15_000) {
  const deadline = Date.now() + timeoutMs;
  let last = null;
  while (Date.now() < deadline) {
    last = await probe();
    if (last !== null && last !== undefined && last !== false) {
      return last;
    }
    await sleep(250);
  }
  return last;
}

/** 起打包的后端，读 stdout 的 READY 行。 */
function startBackend() {
  const child = spawn(EXE, ['--mode=integrated', '--port=0', `--work-dir=${WORK}`], {
    stdio: ['ignore', 'pipe', 'pipe'],
    windowsHide: true,
  });
  const stderr = [];
  child.stderr.on('data', (chunk) => {
    stderr.push(String(chunk));
    if (stderr.length > 200) {
      stderr.shift();
    }
  });
  const ready = new Promise((resolve, reject) => {
    let buffer = '';
    const timer = setTimeout(() => reject(new Error('后端 30 秒内没有 READY')), 30_000);
    child.stdout.on('data', (chunk) => {
      buffer += String(chunk);
      const hit = /READY[^\r\n]*http=(\d+)/.exec(buffer);
      if (hit !== null) {
        clearTimeout(timer);
        resolve(`http://127.0.0.1:${hit[1]}`);
      }
    });
    child.on('exit', (code) => {
      clearTimeout(timer);
      reject(new Error(`后端在 READY 之前退出（code=${code}）:\n${stderr.join('').slice(-2000)}`));
    });
  });
  return { child, ready, stderr };
}

const sml = (...lines) => `${lines.join('\n')}\n.`;

async function main() {
  log(`工作目录: ${WORK}`);
  log(`后端程序: ${EXE}`);
  log('');

  const backend = startBackend();
  backendHandle = backend;
  const base = await backend.ready;
  log(`后端就绪: ${base}`);
  log('');

  const health = await api(base, '/api/v1/health');
  record('健康检查返回 JSON', health.ok && health.body?.ok === true && health.body?.mode === 'integrated',
    `${health.status} mode=${health.body?.mode}`);

  /* ---- 1. 新建设备（内置 etch 模板）---- */
  const templates = await api(base, '/api/v1/config-templates');
  const templateIds = (templates.body?.templates ?? []).map((item) => item.id);
  record('内置设备模板目录可读', templateIds.includes('etch'), `templates=${templateIds.join(',')}`);

  const configName = 'e2e-trace-etch.json';
  const created = await post(base, '/api/v1/configs', { name: configName, template: 'etch' });
  record('新建设备（配置）', created.status === 201, `${created.status} ${created.text.slice(0, 160)}`);

  const snapshot = await api(base, `/api/v1/configs/${encodeURIComponent(configName)}`);
  const configText = snapshot.body?.text ?? '';
  let parsed = null;
  try {
    parsed = JSON.parse(configText);
  } catch {
    parsed = null;
  }
  const deviceId = parsed?.session?.deviceId;
  const port = parsed?.session?.port;
  record('读到配置原文并解析出 deviceId / 端口', typeof deviceId === 'number' && typeof port === 'number',
    `deviceId=${deviceId} port=${port} 配置 ${configText.length} 字节`);

  /* ---- 2. 启动设备会话（HSMS 监听）---- */
  const sessionCreate = await post(base, '/api/v1/sessions', { config: configName });
  record('启动设备会话', sessionCreate.status === 201, `${sessionCreate.status} ${sessionCreate.text.slice(0, 160)}`);

  const sessions = await waitFor('会话出现', async () => {
    const page = await api(base, '/api/v1/sessions');
    const list = page.body?.sessions ?? [];
    return list.find((item) => item.config === configName) ?? null;
  });
  const sessionId = sessions?.id ?? '';
  record('设备会话在列表中且已监听', sessionId !== '' && sessions?.state !== 'CLOSED',
    `id=${sessionId} state=${sessions?.state} control=${sessions?.controlState}`);

  const catalog = await api(base, `/api/v1/sessions/${encodeURIComponent(sessionId)}/catalog`);
  const svids = (catalog.body?.svids ?? []).map((item) => item.svid).slice(0, 3);
  record('设备目录（SVID）可读', svids.length >= 2, `svids=${svids.join(',')} 共 ${(catalog.body?.svids ?? []).length} 个`);

  /* ---- 3. 新建主机并连接设备 ---- */
  /* 后端的新建接口会在建实例时直接建链（HostSimRegistry.open → sim.connect），
     所以这里只等相位；只有停在 DISCONNECTED/FAILED 时才补一次「连接」（与界面按钮等价）。 */
  const readHost = async () => {
    const page = await api(base, '/api/v2/hosts');
    return (page.body?.hosts ?? []).find((item) => item.id === hostId) ?? null;
  };

  const hostCreate = await post(base, '/api/v2/hosts', {
    name: 'e2e-trace-host', host: '127.0.0.1', port, deviceId,
  });
  const hostId = hostCreate.body?.hostId ?? '';
  record('新建主机实例', hostCreate.status === 201 && hostId !== '',
    `${hostCreate.status} hostId=${hostId} target=127.0.0.1:${port} deviceId=${deviceId}`);

  let hostState = await waitFor('主机相位 CONNECTED', async () => {
    const current = await readHost();
    return current?.phase === 'CONNECTED' ? current : null;
  }, 10_000);
  if (hostState === null) {
    const current = await readHost();
    if (current?.phase === 'DISCONNECTED' || current?.phase === 'FAILED') {
      const retry = await post(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/connect`);
      log(`  新建时未连上（phase=${current?.phase}），补一次显式连接: ${retry.status}`);
      hostState = await waitFor('主机相位 CONNECTED', async () => {
        const again = await readHost();
        return again?.phase === 'CONNECTED' ? again : null;
      }, 10_000);
    }
  }
  record('主机连上设备（HSMS ACTIVE）', hostState?.phase === 'CONNECTED',
    `phase=${hostState?.phase ?? '(超时)'} open=${hostState?.open} target=127.0.0.1:${port}`);

  /* /connect 幂等：已连接时再点一次「连接」必须 200（不能 502 弹「连接失败」） */
  const reconnect = await post(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/connect`);
  record('重复连接是幂等的（已连接再点「连接」回 200）',
    reconnect.status === 200 && reconnect.body?.ok !== false,
    `${reconnect.status} ${reconnect.text.slice(0, 80)}`);
  const afterReconnect = await readHost();
  record('重复连接后链路仍是 CONNECTED', afterReconnect?.phase === 'CONNECTED',
    `phase=${afterReconnect?.phase}`);

  /* ---- 4. 让设备上线（S1F13 通信 + S1F17 上线）---- */
  const f13 = await post(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/send`, { sml: sml('S1F13 W') });
  record('主机发 S1F13，设备回 S1F14', f13.ok && f13.body?.replyFunction === 14,
    `reply=S${f13.body?.replyStream}F${f13.body?.replyFunction} ${f13.body?.replySml ?? ''}`);

  const f17 = await post(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/send`, { sml: sml('S1F17 W') });
  record('主机发 S1F17，设备回 S1F18（ONLACK=0）', f17.ok && f17.body?.replyFunction === 18,
    `reply=S${f17.body?.replyStream}F${f17.body?.replyFunction} ${f17.body?.replySml ?? ''}`);

  const online = await waitFor('设备上线', async () => {
    const page = await api(base, '/api/v1/sessions');
    return (page.body?.sessions ?? []).find((item) => item.id === sessionId)?.controlState === 'ONLINE'
      ? 'ONLINE' : null;
  }, 5_000);
  record('设备控制状态为 ONLINE', online === 'ONLINE', `controlState=${online ?? '(仍非在线)'}`);

  /* ---- 5. 数据采集（S1F3 轮询）：两份采集计划并行，各采各的 ---- */
  const ceids = (catalog.body?.ceids ?? []).map((item) => item.ceid);
  const planA = {
    planId: 'plan-a', startCeid: ceids[0] ?? 2001, stopCeid: ceids[2] ?? 2003,
    intervalMs: 500, svids: [{ id: svids[0] }],
  };
  const planB = {
    planId: 'plan-b', startCeid: ceids[1] ?? 2002, stopCeid: ceids[2] ?? 2003,
    intervalMs: 500, svids: [{ id: svids[1] }],
  };
  log(`  CEID 目录: [${ceids.join(', ')}]；A=${planA.startCeid}/${planA.stopCeid} SVID ${planA.svids[0].id}，`
    + `B=${planB.startCeid}/${planB.stopCeid} SVID ${planB.svids[0].id}`);

  const base_ = `/api/v1/host-sims/${encodeURIComponent(hostId)}/collector`;
  const readCollector = async () => {
    const page = await api(base, base_);
    const list = page.body?.collectors ?? [];
    return {
      raw: page.body,
      a: list.find((item) => item.planId === planA.planId) ?? null,
      b: list.find((item) => item.planId === planB.planId) ?? null,
    };
  };

  for (const plan of [planA, planB]) {
    const saved = await post(base, base_, plan);
    record(`下发采集计划 ${plan.planId}（500ms，SVID ${plan.svids[0].id}）`,
      saved.ok && saved.body?.ok === true, `${saved.status} ${saved.text.slice(0, 120)}`);
  }

  for (const plan of [planA, planB]) {
    const started = await post(base, `${base_}/start?planId=${encodeURIComponent(plan.planId)}`);
    record(`启动采集计划 ${plan.planId}`, started.ok && started.body?.ok === true, `${started.status}`);
  }
  const armed = await readCollector();
  record('两份计划都进入待命（等各自的启动 CEID）',
    armed.a?.armed === true && armed.b?.armed === true,
    `A armed=${armed.a?.armed} · B armed=${armed.b?.armed}`);

  /* 设备分别上报两份计划各自的启动 CEID */
  for (const plan of [planA, planB]) {
    const triggered = await post(base, `/api/v1/sessions/${encodeURIComponent(sessionId)}/ceid/${plan.startCeid}`);
    record(`设备触发 CEID ${plan.startCeid}（对应 ${plan.planId} 的启动）`, triggered.ok, `${triggered.status}`);
  }

  const bothRunning = await waitFor('A、B 同时采到数据', async () => {
    const state = await readCollector();
    return (state.a?.samples ?? 0) >= 2 && (state.b?.samples ?? 0) >= 2 ? state : null;
  }, 20_000);
  record('两份计划同时采集（A 与 B 都有数据）',
    bothRunning.a?.running === true && bothRunning.b?.running === true
      && (bothRunning.a?.samples ?? 0) >= 2 && (bothRunning.b?.samples ?? 0) >= 2,
    `A samples=${bothRunning.a?.samples ?? 0} series=${JSON.stringify((bothRunning.a?.series ?? []).map((s) => s.svid))} ｜ `
      + `B samples=${bothRunning.b?.samples ?? 0} series=${JSON.stringify((bothRunning.b?.series ?? []).map((s) => s.svid))}`);

  /* 连续采样：曾经出现过「收到启动 CEID 只发一两条 S1F3 就没数据」 */
  const beforeA = bothRunning.a?.samples ?? 0;
  const beforeB = bothRunning.b?.samples ?? 0;
  await sleep(2_500);
  const later = await readCollector();
  record('两份都在持续采（2.5 秒后样本继续增长）',
    (later.a?.samples ?? 0) > beforeA && (later.b?.samples ?? 0) > beforeB,
    `A ${beforeA} → ${later.a?.samples ?? '?'} · B ${beforeB} → ${later.b?.samples ?? '?'}`);

  /* 重复启动幂等：再点一次 A 的「启动」不能把 A 按回待命，也不能影响 B */
  const restart = await post(base, `${base_}/start?planId=${encodeURIComponent(planA.planId)}`);
  const afterRestart = await readCollector();
  record('重复启动幂等（A 仍在采，B 不受影响）',
    restart.ok && afterRestart.a?.running === true && afterRestart.b?.running === true,
    `${restart.status} A running=${afterRestart.a?.running} armed=${afterRestart.a?.armed} · B running=${afterRestart.b?.running}`);

  /* 只停 A：B 必须继续采 */
  const stopA = await post(base, `${base_}/stop?planId=${encodeURIComponent(planA.planId)}`);
  const afterStopA = await readCollector();
  record('停掉 A 只影响 A（B 继续采）',
    stopA.ok && afterStopA.a?.running !== true && afterStopA.b?.running === true,
    `${stopA.status} A running=${afterStopA.a?.running} · B running=${afterStopA.b?.running}`);

  const stopAll = await post(base, `${base_}/stop`);
  const afterStopAll = await readCollector();
  record('不带计划的停止 = 全部停', stopAll.ok && afterStopAll.a?.running !== true && afterStopAll.b?.running !== true,
    `${stopAll.status} A=${afterStopAll.a?.running} B=${afterStopAll.b?.running}`);

  /* ---- 5b. 删采集方案 = 那份采集器自动停并消失（不留后台孤儿）---- */
  const plansUrl = `/api/v2/hosts/${encodeURIComponent(hostId)}/plans`;
  const planC = {
    kind: 'collect', id: 'plan-c', name: '删除用例',
    startCeid: String(ceids[0] ?? 2001), stopCeid: String(ceids[2] ?? 2003),
    intervalMs: '500', svids: String(svids[0]), createdAt: Date.now(),
  };
  const savedC = await post(base, plansUrl, planC);
  record('新建一份采集方案（方案库）', savedC.ok && savedC.body?.ok === true, `${savedC.status}`);

  await post(base, base_, {
    planId: 'plan-c',
    startCeid: Number(planC.startCeid),
    stopCeid: Number(planC.stopCeid),
    intervalMs: 500,
    svids: [{ id: svids[0] }],
  });

  await post(base, `${base_}/start?planId=plan-c`);
  await post(base, `/api/v1/sessions/${encodeURIComponent(sessionId)}/ceid/${planC.startCeid}`);
  const planCRunning = await waitFor('plan-c 开跑', async () => {
    const page = await api(base, base_);
    const item = (page.body?.collectors ?? []).find((entry) => entry.planId === 'plan-c');
    return item?.running === true ? item : null;
  }, 15_000);
  record('这份方案正在采集', planCRunning?.running === true, `samples=${planCRunning?.samples ?? 0}`);

  const deleteC = await api(base, `${plansUrl}/plan-c?kind=collect`, { method: 'DELETE' });
  const afterDelete = await api(base, base_);
  const remaining = (afterDelete.body?.collectors ?? []).map((item) => item.planId);
  record('删掉采集方案 = 那份采集器自动停并消失',
    deleteC.ok && !remaining.includes('plan-c'),
    `${deleteC.status} 剩余=[${remaining.join(',')}]`);
  /* ---- 6. Trace 采集（S2F23 → S6F1）---- */
  const traceSml = sml(
    'S2F23 W',
    '<L',
    '  <U4 7>',
    '  <A "000001">',
    '  <U4 5>',
    '  <U4 1>',
    '  <L',
    ...svids.slice(0, 2).map((id) => `    <U4 ${id}>`),
    '  >',
    '>',
  );
  const traceInit = await post(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/send`, { sml: traceSml });
  const tiaack = String(traceInit.body?.replySml ?? '').replace(/\s+/g, ' ').slice(0, 80);
  record('主机发 S2F23，设备回 S2F24（TIAACK=0）',
    traceInit.ok && traceInit.body?.replyFunction === 24 && /<B 0x0+>/.test(tiaack),
    `reply=S${traceInit.body?.replyStream}F${traceInit.body?.replyFunction} ${tiaack}`);

  const frames = await waitFor('收到 S6F1', async () => {
    const page = await api(base, '/api/v1/messages?stream=6&function=1&limit=200');
    const list = (page.body?.messages ?? []).filter((item) => item.direction === 'out');
    return list.length >= 3 ? list : null;
  }, 20_000);
  const frameList = frames ?? [];
  record('收到设备上报的 S6F1（Trace 数据）', frameList.length >= 3,
    `${frameList.length} 帧，首帧 body=${(frameList[0]?.body ?? '').replace(/\s+/g, ' ').slice(0, 120)}`);

  const lastFrame = frameList.at(-1)?.body ?? '';
  const trids = [...lastFrame.matchAll(/<U4\s+(\d+)>/g)].map((match) => Number(match[1]));
  const samples = [...lastFrame.matchAll(/<F4\s+([-\d.]+)>/g)].map((match) => Number(match[1]));
  record('S6F1 帧含 TRID / SMLN / STIME / 采样值',
    trids.includes(7) && samples.length >= 2 && samples.every((value) => Number.isFinite(value)),
    `TRID/SMLN=[${trids.join(', ')}] 采样值=[${samples.map((v) => v.toFixed(2)).join(', ')}]`);

  /* 主机视角的收发记录（界面 Trace 曲线就吃这份） */
  const hostFrames = await waitFor('主机视角 S6F1', async () => {
    const page = await api(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/messages?limit=200`);
    const list = (page.body?.messages ?? []).filter((item) => item.stream === 6 && item.function === 1);
    return list.length >= 2 ? list : null;
  }, 15_000);
  record('主机视角可见 S6F1（界面曲线数据源）', (hostFrames ?? []).length >= 2, `${(hostFrames ?? []).length} 帧`);

  const stopTrace = await post(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/send`, {
    sml: sml('S2F23 W', '<L', '  <U4 7>', '  <A "000001">', '  <U4 0>', '  <U4 1>', '  <L', '  >', '>'),
  });
  record('TOTSMP=0 终止该 TRID', stopTrace.ok && stopTrace.body?.replyFunction === 24,
    `reply=S${stopTrace.body?.replyStream}F${stopTrace.body?.replyFunction}`);

  /* ---- 7. 收尾：停会话、删主机（保持环境干净，工作目录最后整体删除）---- */
  await post(base, `/api/v2/hosts/${encodeURIComponent(hostId)}/disconnect`);
  const hostDelete = await api(base, `/api/v2/hosts/${encodeURIComponent(hostId)}`, { method: 'DELETE' });
  record('删除主机实例', hostDelete.ok, `${hostDelete.status}`);

  const sessionDelete = await api(base, `/api/v1/sessions/${encodeURIComponent(sessionId)}`, { method: 'DELETE' });
  record('关闭设备会话', sessionDelete.ok, `${sessionDelete.status}`);

  const configDelete = await api(base, `/api/v1/configs/${encodeURIComponent(configName)}`, { method: 'DELETE' });
  record('删除设备配置', configDelete.ok, `${configDelete.status}`);
}

/** 停后端并等它真正退出，否则 Windows 上工作目录删不掉。 */
async function stopBackend(child) {
  if (child === null || child.exitCode !== null) {
    return;
  }
  await new Promise((resolve) => {
    const timer = setTimeout(resolve, 8_000);
    child.once('exit', () => {
      clearTimeout(timer);
      resolve();
    });
    child.kill();
  });
}

async function removeDir(path) {
  for (let attempt = 0; attempt < 10; attempt += 1) {
    try {
      rmSync(path, { recursive: true, force: true });
      return true;
    } catch {
      await sleep(300);
    }
  }
  return false;
}

main()
  .catch((error) => {
    failures += 1;
    log('');
    log(`异常: ${error?.message ?? error}`);
  })
  .finally(async () => {
    await stopBackend(backendHandle?.child ?? null);
    backendHandle?.child.stdout?.destroy();
    backendHandle?.child.stderr?.destroy();
    if (!(await removeDir(WORK))) {
      log(`清理工作目录失败（可能仍被占用）: ${WORK}`);
    }
    log('');
    log(`结果: ${steps.filter((item) => item.ok).length}/${steps.length} 项通过${failures === 0 ? '，全部通过' : `，${failures} 项失败`}`);
    process.exit(failures === 0 ? 0 : 1);
  });
