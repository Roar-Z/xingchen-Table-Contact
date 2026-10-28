@echo off
chcp 65001 >nul
cd /d "%~dp0"

if not exist node_modules (
    echo 首次运行，正在安装前端依赖...
    call npm install
    if errorlevel 1 (
        echo.
        echo 依赖安装失败，请检查 Node.js 和网络。
        pause
        exit /b 1
    )
)

echo.
echo 前端开发服务器启动中，默认 http://127.0.0.1:5173
echo 请确认后端已经在 8000 端口启动。
echo.
call npm run dev

pause
