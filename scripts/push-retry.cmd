@echo off
REM Push main with system proxy + retries (added in v0.0.78 handoff).
REM Usage: cmd /c scripts\push-retry.cmd
REM Requires: local proxy at 127.0.0.1:7890 (direct github.com is blocked on this machine).
REM Note: keep this file ASCII-only; cmd.exe garbles UTF-8 comments under GBK console.
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
