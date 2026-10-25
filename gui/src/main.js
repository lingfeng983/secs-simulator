const invoke = window.__TAURI__.core.invoke;

const $ = (id) => document.getElementById(id);
const status = $("status");

function setStatus(text, kind = "") {
  status.textContent = text;
  status.className = `status ${kind}`.trim();
}

document.querySelectorAll(".tab").forEach((tab) => {
  tab.addEventListener("click", () => {
    document.querySelectorAll(".tab").forEach((t) => t.classList.toggle("active", t === tab));
    document.querySelectorAll(".panel").forEach((panel) => {
      panel.classList.toggle("active", panel.id === `panel-${tab.dataset.tab}`);
    });
  });
});

const SETTINGS_KEY = "secs-sim-gui.settings";

function loadSettings() {
  try {
    const saved = JSON.parse(localStorage.getItem(SETTINGS_KEY) || "{}");
    if (saved.host) $("host").value = saved.host;
    if (saved.port) $("port").value = saved.port;
    $("tls").checked = !!saved.tls;
    $("demo").checked = saved.demo !== false;
    if (saved.tab === "remote") {
      document.querySelector('.tab[data-tab="remote"]').click();
    }
  } catch {
    /* 设置损坏就用默认值 */
  }
}

function saveSettings() {
  const data = {
    host: $("host").value.trim(),
    port: $("port").value.trim(),
    tls: $("tls").checked,
    demo: $("demo").checked,
    tab: document.querySelector(".tab.active").dataset.tab,
  };
  localStorage.setItem(SETTINGS_KEY, JSON.stringify(data));
}

function remoteUrl() {
  const host = $("host").value.trim();
  const port = Number($("port").value);
  if (!host) throw new Error("请填写服务器地址");
  if (!Number.isInteger(port) || port < 1 || port > 65535) throw new Error("端口必须是 1..65535");
  const scheme = $("tls").checked ? "https" : "http";
  return `${scheme}://${host}:${port}/`;
}

async function open(url) {
  await invoke("open_main", { url });
}

let busy = false;

async function run(button, label, task) {
  if (busy) return;
  busy = true;
  const buttons = document.querySelectorAll("button");
  buttons.forEach((b) => (b.disabled = true));
  setStatus(label);
  try {
    const message = await task();
    setStatus(message || "已打开界面", "ok");
    saveSettings();
  } catch (error) {
    setStatus(String(error), "error");
  } finally {
    busy = false;
    buttons.forEach((b) => (b.disabled = false));
  }
}

$("start-local").addEventListener("click", () =>
  run($("start-local"), "正在启动本机模拟器…", async () => {
    const url = await invoke("start_local", { demo: $("demo").checked });
    await open(url);
    return "本机模拟器已启动。关闭窗口后仍可在托盘恢复。";
  })
);

$("stop-local").addEventListener("click", () =>
  run($("stop-local"), "正在停止…", async () => {
    await invoke("stop_local");
    return "本机模拟器已停止。";
  })
);

$("connect").addEventListener("click", () => {
  let url;
  try {
    url = remoteUrl();
  } catch (error) {
    setStatus(String(error), "error");
    return;
  }
  run($("connect"), "正在连接服务器…", async () => {
    await open(url);
    return `已打开 ${url}`;
  });
});

loadSettings();
