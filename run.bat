@echo off
chcp 65001 >nul
cd /d "%~dp0"

echo.
echo ╔══════════════════════════════════════════════════╗
echo ║         财联社电报 - 实时监听 v5               ║
echo ╚══════════════════════════════════════════════════╝
echo.

where mvn >nul 2>nul
if %errorlevel% equ 0 (
    echo [√] Maven 已检测到
    echo.
    mvn compile -q && mvn exec:java -q
) else (
    echo [×] 未检测到 Maven，请先安装 Maven 并配置环境变量
    echo     下载地址: https://maven.apache.org/download.cgi
    pause
    exit /b 1
)
