@echo off
chcp 65001 >nul
cd /d "%~dp0"

rem ============================================================
rem  一键启动模板 —— 复制成 start_monitor.bat 后修改 Topic
rem      copy start_monitor.example.bat start_monitor.bat
rem  Topic 请用手机 App 首次启动时自动生成的随机串（形如 ltc-fall-k7m2xq9p）
rem  这个文件里含“密钥”，不要提交到仓库（已在 .gitignore 中排除 start_monitor.bat）
rem ============================================================
set NTFY_SERVER=https://ntfy.sh
set NTFY_TOPIC=在此填写手机App里的Topic
set DINGTALK_WEBHOOK=

echo.
echo  [配置] 中转服务 : %NTFY_SERVER%
echo  [配置] 频道Topic: %NTFY_TOPIC%
echo.
pause

python elderly_fall_monitor.py

echo.
echo  监测已退出。
pause
