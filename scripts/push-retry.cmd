@echo off
REM 推送 main（带系统代理 + 8 次重试）。v0.0.78 交接新增。
REM 用法：cmd /c scripts\push-retry.cmd
REM 前置：系统代理 127.0.0.1:7890 在运行（本机 github.com 直连不通）
setlocal enabledelayedexpansion
cd /d "%~dp0.."
set GIT_TERMINAL_PROMPT=0
set /a tries=0
:retry
set /a tries+=1
echo [push attempt !tries!]
git -c http.proxy=http://127.0.0.1:7890 push origin main && (echo PUSH_OK & exit /b 0)
if !tries! geq 8 (echo PUSH_FAILED_ALL_RETRIES & exit /b 1)
ping -n 9 127.0.0.1 >nul
goto retry
