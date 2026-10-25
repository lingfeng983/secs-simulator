#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! SECS Simulator 桌面壳（Windows 安装程序形态）。
//!
//! 两层窗口：
//! - `launcher`：本地页面，配置「本机启动」或「远程服务器地址」；
//! - `main`：直接加载模拟器的 Web UI——本机模式加载本地后端托管的页面，
//!   远程模式直接加载 Linux 服务端（jar 包）托管的同源页面。
//!
//! 远程页面不参与 Tauri IPC，因此不需要给远端域名开权限。

use std::io::{BufRead, BufReader};
use std::path::{Path, PathBuf};
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, TrayIconBuilder, TrayIconEvent};
use tauri::{Manager, RunEvent, State, WebviewUrl, WebviewWindowBuilder};

struct Backend(Mutex<Option<Child>>);

impl Backend {
    fn new() -> Self {
        Self(Mutex::new(None))
    }

    fn stop(&self) {
        if let Ok(mut guard) = self.0.lock() {
            if let Some(mut child) = guard.take() {
                let _ = child.kill();
                let _ = child.wait();
            }
        }
    }
}

fn main() {
    if std::env::var("SECS_GUI_SELFTEST").is_ok() {
        self_test();
        return;
    }

    tauri::Builder::default()
        .manage(Backend::new())
        .setup(|app| {
            let launcher = app
                .get_webview_window("launcher")
                .ok_or("launcher window missing")?;
            if let Ok(icon) = tauri::image::Image::from_bytes(include_bytes!("../icons/128x128.png")) {
                let _ = launcher.set_icon(icon);
            }

            let settings = MenuItem::with_id(app, "settings", "连接设置", true, None::<&str>)?;
            let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &settings, &quit])?;

            let tray = TrayIconBuilder::with_id("secs-sim")
                .icon(app.default_window_icon().cloned().ok_or("missing app icon")?)
                .tooltip("SECS Simulator")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "quit" => {
                        if let Some(state) = app.try_state::<Backend>() {
                            state.stop();
                        }
                        app.exit(0);
                    }
                    _ => show_launcher(app),
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::DoubleClick {
                        button: MouseButton::Left,
                        ..
                    } = event
                    {
                        show_launcher(tray.app_handle());
                    }
                })
                .build(app)?;
            let _ = tray.set_icon(Some(app.default_window_icon().cloned().ok_or("missing app icon")?));

            Ok(())
        })
        .on_window_event(|window, event| {
            // 关掉 launcher 只隐藏，托盘和本机后端继续跑。
            if window.label() == "launcher" {
                if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![start_local, stop_local, open_main])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(state) = app.try_state::<Backend>() {
                    state.stop();
                }
            }
        });
}

fn show_launcher(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("launcher") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

/// 资源目录：安装包是 `<install>/resources`，便携包是 `<exe>/resources`。
fn resource_root(app: &tauri::AppHandle) -> PathBuf {
    let mut candidates = Vec::new();
    if let Ok(dir) = app.path().resource_dir() {
        candidates.push(dir.join("resources"));
        candidates.push(dir);
    }
    if let Ok(exe) = std::env::current_exe() {
        if let Some(dir) = exe.parent() {
            candidates.push(dir.join("resources"));
            candidates.push(dir.to_path_buf());
            if let Some(parent) = dir.parent() {
                candidates.push(parent.join("resources"));
            }
        }
    }

    for dir in &candidates {
        if backend_command(dir).is_some() {
            return dir.clone();
        }
    }
    candidates.into_iter().next().unwrap_or_else(|| PathBuf::from("."))
}

fn backend_command(resources: &Path) -> Option<PathBuf> {
    let exe = resources.join("secs-sim.exe");
    exe.exists().then_some(exe)
}

fn web_root(resources: &Path) -> PathBuf {
    resources.join("web")
}

fn work_dir(app: &tauri::AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .map(|p| p.join("SecsSimulator"))
        .unwrap_or_else(|_| PathBuf::from("SecsSimulator"))
}

#[tauri::command]
fn start_local(app: tauri::AppHandle, state: State<'_, Backend>, demo: bool) -> Result<String, String> {
    state.stop();

    let resources = resource_root(&app);
    let work = work_dir(&app);
    let (child, url) = spawn_backend(&resources, &work, demo)?;
    if let Ok(mut guard) = state.0.lock() {
        *guard = Some(child);
    }
    Ok(url)
}

/// 拉起后端并读 READY。后端是打包进安装程序的 secs-sim.exe（原生镜像，无需 JVM）。
fn spawn_backend(resources: &Path, work: &Path, demo: bool) -> Result<(Child, String), String> {
    let backend = backend_command(resources)
        .ok_or_else(|| format!("找不到后端程序，资源目录: {}", resources.display()))?;
    let web = web_root(resources);
    std::fs::create_dir_all(work).map_err(|e| format!("创建工作目录失败: {e}"))?;

    let mut command = Command::new(&backend);
    command
        .arg("--mode=integrated")
        .arg("--port=0")
        .arg("--hsms-port=5000")
        .arg(format!("--web-root={}", web.display()))
        .arg(format!("--work-dir={}", work.display()));
    if demo {
        command.arg("--demo-session");
    }
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    command.stdout(Stdio::piped()).stderr(Stdio::piped());

    let mut child = command.spawn().map_err(|e| format!("启动后端失败: {e}"))?;
    let stdout = child.stdout.take().ok_or("无法读取后端输出")?;
    let stderr = child.stderr.take().ok_or("无法读取后端错误输出")?;

    let (tx, rx) = mpsc::channel::<String>();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = tx.send(line);
        }
    });
    let errors = Arc::new(Mutex::new(String::new()));
    let errors_for_thread = Arc::clone(&errors);
    std::thread::spawn(move || {
        for line in BufReader::new(stderr).lines().map_while(Result::ok) {
            if let Ok(mut guard) = errors_for_thread.lock() {
                guard.push_str(&line);
                guard.push('\n');
            }
        }
    });

    let deadline = Instant::now() + Duration::from_secs(30);
    let mut url = None;
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(line) if line.starts_with("READY ") => {
                url = Some(ready_url(&line));
                break;
            }
            Ok(_) => {}
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }

    let url = match url {
        Some(url) => url,
        None => {
            let _ = child.kill();
            let _ = child.wait();
            let detail = errors.lock().map(|s| s.trim().to_string()).unwrap_or_default();
            return Err(if detail.is_empty() {
                "后端 30 秒内没有就绪".to_string()
            } else {
                format!("后端启动失败:\n{detail}")
            });
        }
    };

    Ok((child, url))
}

fn self_test() {
    let exe = std::env::current_exe().expect("current exe");
    let resources = exe.parent().expect("exe parent").join("resources");
    let work = std::env::temp_dir().join("secs-gui-selftest");
    let out = std::env::temp_dir().join("secs-gui-selftest.txt");
    match spawn_backend(&resources, &work, true) {
        Ok((mut child, url)) => {
            let _ = std::fs::write(&out, format!("OK {url}"));
            std::thread::sleep(Duration::from_secs(25));
            let _ = child.kill();
            let _ = child.wait();
        }
        Err(error) => {
            let _ = std::fs::write(&out, format!("ERROR {error}"));
        }
    }
}

fn ready_url(line: &str) -> String {
    let mut port = "0".to_string();
    let mut token = String::new();
    for part in line.split_whitespace() {
        if let Some(value) = part.strip_prefix("http=") {
            port = value.to_string();
        }
        if let Some(value) = part.strip_prefix("token=") {
            token = value.to_string();
        }
    }
    if token.is_empty() {
        format!("http://127.0.0.1:{port}/")
    } else {
        format!("http://127.0.0.1:{port}/#token={token}")
    }
}

#[tauri::command]
fn stop_local(state: State<'_, Backend>) {
    state.stop();
}

#[tauri::command]
fn open_main(app: tauri::AppHandle, url: String) -> Result<(), String> {
    let parsed: url::Url = url.parse().map_err(|e| format!("地址无效: {e}"))?;
    if !matches!(parsed.scheme(), "http" | "https") {
        return Err("只支持 http/https 地址".to_string());
    }

    if let Some(window) = app.get_webview_window("main") {
        let _ = window.close();
    }

    let window = WebviewWindowBuilder::new(&app, "main", WebviewUrl::External(parsed))
        .title("SECS Simulator")
        .inner_size(1320.0, 900.0)
        .min_inner_size(900.0, 600.0)
        .center()
        .build()
        .map_err(|e| format!("打开窗口失败: {e}"))?;

    if let Ok(icon) = tauri::image::Image::from_bytes(include_bytes!("../icons/128x128.png")) {
        let _ = window.set_icon(icon);
    }
    let _ = window.set_focus();
    Ok(())
}
