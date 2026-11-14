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

/// 后端 stderr 的保留上限：只留最后 64 KiB 用于报错，避免长跑时无限增长。
const STDERR_LIMIT: usize = 64 * 1024;

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
        // 单实例：第二次启动不再开一份新进程（两份会抢同一组 HSMS 端口与同一个 work-dir），
        // 而是把已有窗口拉到前台。
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| {
            show_main(app);
        }))
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

/// 把后端进程放进「GUI 消失就杀掉成员」的 Job Object（仅 Windows）。
///
/// 任务管理器结束进程、崩溃、断电式退出时，Rust 侧的清理代码不会执行；
/// 只有内核在关闭最后一个 Job 句柄时才会杀掉成员进程，所以句柄要故意留着不关。
#[cfg(windows)]
fn attach_to_kill_on_close_job(child: &Child) -> Result<(), String> {
    use std::os::windows::io::AsRawHandle;
    use std::ptr;
    use std::sync::OnceLock;
    use windows_sys::Win32::Foundation::{CloseHandle, HANDLE};
    use windows_sys::Win32::System::JobObjects::{
        AssignProcessToJobObject, CreateJobObjectW, JobObjectExtendedLimitInformation,
        SetInformationJobObject, JOBOBJECT_EXTENDED_LIMIT_INFORMATION,
        JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE,
    };

    // 进程级单例：句柄泄漏是刻意的（关掉句柄 = 立刻杀掉 Job 里的后端）。
    static JOB: OnceLock<usize> = OnceLock::new();

    let job = *JOB.get_or_init(|| unsafe {
        let handle = CreateJobObjectW(ptr::null(), ptr::null());
        if handle.is_null() {
            return 0;
        }
        let mut info: JOBOBJECT_EXTENDED_LIMIT_INFORMATION = std::mem::zeroed();
        info.BasicLimitInformation.LimitFlags = JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE;
        let size = std::mem::size_of::<JOBOBJECT_EXTENDED_LIMIT_INFORMATION>() as u32;
        let ok = SetInformationJobObject(
            handle,
            JobObjectExtendedLimitInformation,
            &info as *const _ as *const core::ffi::c_void,
            size,
        );
        if ok == 0 {
            let _ = CloseHandle(handle);
            return 0;
        }
        handle as usize
    });

    if job == 0 {
        return Err("CreateJobObjectW / SetInformationJobObject 失败".to_string());
    }
    let process: HANDLE = child.as_raw_handle() as HANDLE;
    if unsafe { AssignProcessToJobObject(job as HANDLE, process) } == 0 {
        return Err("AssignProcessToJobObject 失败（进程可能已在别的 Job 里）".to_string());
    }
    Ok(())
}

/// 非 Windows：没有等价的 Job Object 语义，退化为「进程内正常退出时 kill」。
#[cfg(not(windows))]
fn attach_to_kill_on_close_job(_child: &Child) -> Result<(), String> {
    Ok(())
}

/// 选一个能绑定的 HSMS 端口：5000 起，被占用就顺延，最多试到 5010。
///
/// 探测是「试绑一下立刻放掉」，后端真正绑定时仍可能被抢；但先把最常见的
/// 「上一个后端进程没退干净、5000 还占着」挡掉，比让后端起不来有用。
fn pick_hsms_port() -> u16 {
    for port in 5000..=5010u16 {
        if std::net::TcpListener::bind(("127.0.0.1", port)).is_ok() {
            return port;
        }
    }
    eprintln!("HSMS 端口 5000-5010 都被占用，仍交给后端 5000（预计会启动失败）");
    5000
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

    let hsms_port = pick_hsms_port();
    let mut command = Command::new(&backend);
    command
        .arg("--mode=integrated")
        .arg("--port=0")
        .arg(format!("--hsms-port={hsms_port}"))
        .arg("--demo-session")
        .arg(format!("--work-dir={}", work.display()));
    {
        use std::os::windows::process::CommandExt;
        command.creation_flags(0x0800_0000);
    }
    command.stdout(Stdio::piped()).stderr(Stdio::piped());

    let mut child = command.spawn().map_err(|e| format!("启动后端失败: {e}"))?;
    if let Err(error) = attach_to_kill_on_close_job(&child) {
        // 挂不上不致命：正常退出路径仍会 kill；只是 GUI 被强杀时可能留下孤儿进程。
        eprintln!("后端进程加入 Job Object 失败（继续运行）: {error}");
    }
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
                // 只留最后 64 KiB：长跑时无上限累积会把错误缓冲变成内存泄漏。
                if guard.len() > STDERR_LIMIT {
                    let mut cut = guard.len() - STDERR_LIMIT;
                    while cut < guard.len() && !guard.is_char_boundary(cut) {
                        cut += 1;
                    }
                    guard.drain(..cut);
                }
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
        // 先探活：后端可能已经自己退了（崩溃/被结束），此时"已是本机"不能当成还活着，
        // 否则切回本机会得到一个指向死进程的地址。
        let mut exited: Option<String> = None;
        let alive = {
            let mut backend = state.backend.lock().unwrap();
            match backend.as_mut() {
                Some(child) => match child.try_wait() {
                    Ok(None) => true,
                    Ok(Some(status)) => {
                        exited = Some(format!("已退出（{status}）"));
                        false
                    }
                    Err(error) => {
                        exited = Some(format!("状态探测失败（{error}）"));
                        false
                    }
                },
                None => false,
            }
        };
        if let Some(reason) = exited {
            eprintln!("本机后端{reason}，重新拉起");
            *state.backend.lock().unwrap() = None;
        }
        if alive {
            // 后台还活着且当前就是本机：不折腾（避免白白重启设备会话）
            if let Some(current) = state.connection.lock().unwrap().clone() {
                if current.mode == "local" {
                    return Ok(current);
                }
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
