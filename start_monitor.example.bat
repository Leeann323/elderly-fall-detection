@echo off
rem ============================================================
rem  One-click launcher template
rem    copy start_monitor.example.bat start_monitor.bat
rem
rem  Topic is read from ntfy_topic.txt (single source of truth).
rem  Put ONE line in ntfy_topic.txt, e.g.:
rem      ltc-fall-k7m2xq9p
rem  The same string must be typed into the Android app.
rem ============================================================
cd /d "%~dp0"

set NTFY_SERVER=https://ntfy.sh
set DINGTALK_WEBHOOK=
set NTFY_TOPIC=
for /f "usebackq tokens=* eol=#" %%a in ("ntfy_topic.txt") do if not defined NTFY_TOPIC set "NTFY_TOPIC=%%a"

if not defined NTFY_TOPIC echo [warn] no topic in ntfy_topic.txt - the phone will NOT receive alerts
if defined NTFY_TOPIC echo [cfg] topic  = %NTFY_TOPIC%
echo [cfg] server = %NTFY_SERVER%
echo [tip] the Android app must show the same topic while listening
echo.
pause

python elderly_fall_monitor.py

echo.
echo monitor exited.
pause
