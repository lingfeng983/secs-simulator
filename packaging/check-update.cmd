@echo off
setlocal
if "%SECS_UPDATE_MANIFEST%"=="" (
  echo 请先设置 SECS_UPDATE_MANIFEST 指向更新清单 URL 或本地文件。
  exit /b 2
)
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0update.ps1" -Manifest "%SECS_UPDATE_MANIFEST%" %*
