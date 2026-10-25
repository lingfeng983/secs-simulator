#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

//! SECS Simulator 桌面壳（Windows 安装程序形态）。
//!
//! 单窗口直接加载打包内置的前端（frontendDist），不再有启动选择页。
//! 前端启动时通过 IPC 取后端地址：
//! - 本机模式：拉起打包的 secs-sim.exe（原生镜像，无需 JVM），界面连本机；
//! - 远程模式：不启本机后端，API 直连 Linux jar 服务端（全端点 CORS）。
//!
//! 界面右上角「连接」可随时切换：留空切回本机，填地址切到远程（会停掉本机后端）。
//! 关闭窗口只隐藏到托盘（本机后端继续跑）；托盘菜单退出时停后端。

use std::io::{BufRead, BufReader};
use std::path::PathBuf;
use std::process::{Child, Command, Stdio};
use std::sync::mpsc;
use std::sync::{Arc, Mutex};
use std::time::{Duration, Instant};

use serde::{Deserialize, Serialize};
use tauri::menu::{Menu, MenuItem};
use tauri::tray::{MouseButton, TrayIconBuilder, TrayIconEvent};
use tauri::{Manager, RunEvent, State};

/// 界面可读的当前连接。
#[derive(Clone, Serialize)]
struct ConnectionInfo {
    mode: &'static str,
    url: String,
}

/// 持久化的连接配置（app_data/connection.json），默认本机。
#[derive(Serialize, Deserialize)]
#[serde(tag = "mode", rename_all = "lowercase")]
enum Persisted {
    Local,
    Remote { url: String },
}

struct AppState {
    backend: Mutex<Option<Child>>,
    connection: Mutex<Option<ConnectionInfo>>,
}

impl AppState {
    fn stop_backend(&self) {
        if let Ok(mut guard) = self.backend.lock() {
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
        .manage(AppState {
            backend: Mutex::new(None),
            connection: Mutex::new(None),
        })
        .setup(|app| {
            let state = app.state::<AppState>();

            // 先解析连接（本机模式要等后端 READY），窗口再亮相，避免白屏闪过
            let persisted = load_config(app.handle());
            let resolved = match persisted {
                Persisted::Remote { url } => Ok(ConnectionInfo { mode: "remote", url }),
                Persisted::Local => spawn_local(app.handle())
                    .map(|(child, url)| {
                        *state.backend.lock().unwrap() = Some(child);
                        ConnectionInfo { mode: "local", url }
                    }),
            };
            match resolved {
                Ok(info) => *state.connection.lock().unwrap() = Some(info),
                Err(error) => {
                    // 后端起不来也照常开窗口：界面显示「后端不可达」，可在「连接」里切远程
                    eprintln!("本机后端启动失败: {error}");
                }
            }

            let show = MenuItem::with_id(app, "show", "显示窗口", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "退出", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&show, &quit])?;

            let tray = TrayIconBuilder::with_id("secs-sim")
                .icon(app.default_window_icon().cloned().ok_or("missing app icon")?)
                .tooltip("SECS Simulator")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "quit" => {
                        if let Some(state) = app.try_state::<AppState>() {
                            state.stop_backend();
                        }
                        app.exit(0);
                    }
                    _ => show_main(app),
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::DoubleClick {
                        button: MouseButton::Left,
                        ..
                    } = event
                    {
                        show_main(tray.app_handle());
                    }
                })
                .build(app)?;
            let _ = tray.set_icon(Some(app.default_window_icon().cloned().ok_or("missing app icon")?));

            if let Some(window) = app.get_webview_window("main") {
                let _ = window.show();
                let _ = window.set_focus();
            }
            Ok(())
        })
        .on_window_event(|window, event| {
            // 关窗只隐藏，托盘与本机后端继续跑；退出走托盘菜单
            if window.label() == "main" {
                if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                    api.prevent_close();
                    let _ = window.hide();
                }
            }
        })
        .invoke_handler(tauri::generate_handler![connection_info, switch_backend])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|app, event| {
            if let RunEvent::Exit = event {
                if let Some(state) = app.try_state::<AppState>() {
                    state.stop_backend();
                }
            }
        });
}

fn show_main(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.show();
        let _ = window.unminimize();
        let _ = window.set_focus();
    }
}

// ---- 路径与持久化 ----

fn data_dir(app: &tauri::AppHandle) -> PathBuf {
    app.path()
        .app_data_dir()
        .unwrap_or_else(|_| PathBuf::from("."))
}

fn work_dir(app: &tauri::AppHandle) -> PathBuf {
    data_dir(app).join("SecsSimulator")
}

fn config_file(app: &tauri::AppHandle) -> PathBuf {
    data_dir(app).join("connection.json")
}

fn load_config(app: &tauri::AppHandle) -> Persisted {
    std::fs::read_to_string(config_file(app))
        .ok()
        .and_then(|text| serde_json::from_str(&text).ok())
        .unwrap_or(Persisted::Local)
}

fn save_config(app: &tauri::AppHandle, persisted: &Persisted) -> Result<(), String> {
    let path = config_file(app);
    if let Some(dir) = path.parent() {
        std::fs::create_dir_all(dir).map_err(|e| format!("创建配置目录失败: {e}"))?;
    }
    let text = serde_json::to_string(persisted).map_err(|e| format!("序列化配置失败: {e}"))?;
    std::fs::write(path, text).map_err(|e| format!("写入配置失败: {e}"))
}

// ---- 本机后端 ----

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
        if dir.join("secs-sim.exe").exists() {
            return dir.clone();
        }
    }
    candidates.into_iter().next().unwrap_or_else(|| PathBuf::from("."))
}

/// 拉起本机后端并读 READY；成功返回子进程与 API 地址，调用方负责接管子进程。
fn spawn_local(app: &tauri::AppHandle) -> Result<(Child, String), String> {
    let resources = resource_root(app);
    let backend = resources.join("secs-sim.exe");
    if !backend.exists() {
        return Err(format!("找不到后端程序: {}", backend.display()));
    }

    let work = work_dir(app);
    std::fs::create_dir_all(&work).map_err(|e| format!("创建工作目录失败: {e}"))?;

    let mut command = Command::new(&backend);
    command
        .arg("--mode=integrated")
        .arg("--port=0")
        .arg("--hsms-port=5000")
        .arg("--demo-session")
        .arg(format!("--work-dir={}", work.display()));
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
    let mut port = None;
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(line) if line.starts_with("READY") => {
                if let Some(found) = line
                    .split_whitespace()
                    .find_map(|part| part.strip_prefix("http="))
                {
                    port = Some(found.to_string());
                }
                break;
            }
            Ok(_) => {}
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }

    match port {
        Some(port) => Ok((child, format!("http://127.0.0.1:{port}"))),
        None => {
            let _ = child.kill();
            let _ = child.wait();
            let detail = errors.lock().map(|s| s.trim().to_string()).unwrap_or_default();
            Err(if detail.is_empty() {
                "后端 30 秒内没有就绪".to_string()
            } else {
                format!("后端启动失败:\n{detail}")
            })
        }
    }
}

// ---- IPC 命令 ----

#[tauri::command]
fn connection_info(state: State<AppState>) -> Result<ConnectionInfo, String> {
    state
        .connection
        .lock()
        .unwrap()
        .clone()
        .ok_or_else(|| "本机后端未启动（启动失败），可在「连接」里切换远程服务端".to_string())
}

/// 切换后端：`target` 为 "local" 或远程地址（http/https）。
/// 切到远程会停掉本机后端；切换成功后持久化，前端收到新地址自行重载。
#[tauri::command]
fn switch_backend(
    app: tauri::AppHandle,
    state: State<AppState>,
    target: String,
) -> Result<ConnectionInfo, String> {
    if target.eq_ignore_ascii_case("local") {
        // 已是本机且后端活着：不折腾（避免白白重启设备会话）
        if let Some(current) = state.connection.lock().unwrap().clone() {
            if current.mode == "local" && state.backend.lock().unwrap().is_some() {
                return Ok(current);
            }
        }
        state.stop_backend();
        let (child, url) = spawn_local(&app)?;
        *state.backend.lock().unwrap() = Some(child);
        let next = ConnectionInfo { mode: "local", url };
        save_config(&app, &Persisted::Local)?;
        *state.connection.lock().unwrap() = Some(next.clone());
        Ok(next)
    } else {
        let trimmed = target.trim().trim_end_matches('/').to_string();
        let parsed: url::Url = trimmed.parse().map_err(|e| format!("地址无效: {e}"))?;
        if !matches!(parsed.scheme(), "http" | "https") {
            return Err("只支持 http/https 地址".to_string());
        }
        if let Some(current) = state.connection.lock().unwrap().clone() {
            if current.mode == "remote" && current.url == trimmed {
                return Ok(current);
            }
        }
        state.stop_backend();
        save_config(&app, &Persisted::Remote { url: trimmed.clone() })?;
        let next = ConnectionInfo { mode: "remote", url: trimmed };
        *state.connection.lock().unwrap() = Some(next.clone());
        Ok(next)
    }
}

// ---- 安装包自检：从 resources 拉起后端读 READY ----

fn self_test() {
    let exe = std::env::current_exe().expect("current exe");
    let resources = exe.parent().expect("exe parent").join("resources");
    let work = std::env::temp_dir().join("secs-gui-selftest");
    let out = std::env::temp_dir().join("secs-gui-selftest.txt");

    let backend = resources.join("secs-sim.exe");
    if !backend.exists() {
        let _ = std::fs::write(&out, format!("ERROR missing {}", backend.display()));
        return;
    }
    std::fs::create_dir_all(&work).expect("create work dir");

    let mut command = Command::new(&backend);
    command
        .arg("--mode=integrated")
        .arg("--port=0")
        .arg("--hsms-port=5001")
        .arg("--demo-session")
        .arg(format!("--work-dir={}", work.display()));
    command.stdout(Stdio::piped()).stderr(Stdio::piped());
    let mut child = command.spawn().expect("spawn backend");

    let stdout = child.stdout.take().expect("stdout");
    let (tx, rx) = mpsc::channel::<String>();
    std::thread::spawn(move || {
        for line in BufReader::new(stdout).lines().map_while(Result::ok) {
            let _ = tx.send(line);
        }
    });

    let deadline = Instant::now() + Duration::from_secs(30);
    let mut url = None;
    while Instant::now() < deadline {
        match rx.recv_timeout(Duration::from_millis(200)) {
            Ok(line) if line.starts_with("READY") => {
                let port = line
                    .split_whitespace()
                    .find_map(|part| part.strip_prefix("http="))
                    .unwrap_or("0");
                url = Some(format!("http://127.0.0.1:{port}"));
                break;
            }
            Ok(_) => {}
            Err(mpsc::RecvTimeoutError::Timeout) => {}
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        }
    }

    match url {
        Some(url) => {
            let _ = std::fs::write(&out, format!("OK {url}"));
            std::thread::sleep(Duration::from_secs(25));
        }
        None => {
            let _ = std::fs::write(&out, "ERROR backend not ready");
        }
    }
    let _ = child.kill();
    let _ = child.wait();
}
