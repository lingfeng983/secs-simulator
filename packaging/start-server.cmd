@echo off
rem 启动运行时后端（server 模式）：等价于 start-server.ps1 的双击入口。
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "%~dp0start-server.ps1" %*
