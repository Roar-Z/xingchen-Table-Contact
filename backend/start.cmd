@echo off
chcp 65001 >nul
cd /d "%~dp0"

set JAR=target\huibiao-backend-1.0.0.jar

if not exist "%JAR%" (
    echo 没有找到已构建的 jar，尝试用 Maven 打包...
    where mvn >nul 2>nul
    if errorlevel 1 (
        echo.
        echo 没有找到 mvn 命令。请先安装 Maven 3.9 及以上版本，
        echo 或者在其它机器上执行 mvn -DskipTests package 后，把 target 目录一起带过来。
        echo.
        pause
        exit /b 1
    )
    call mvn -DskipTests package
    if errorlevel 1 (
        echo.
        echo 打包失败，请查看上面的 Maven 输出。
        pause
        exit /b 1
    )
)

echo.
echo 汇表后端启动中，稍后访问 http://127.0.0.1:8000
echo 按 Ctrl+C 停止服务。
echo.
java -jar "%JAR%"

pause
