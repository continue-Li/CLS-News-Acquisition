@echo off
chcp 65001 >nul
echo.
echo ========================================
echo   财联社电报爬虫
echo ========================================
echo.

cd /d "%~dp0"

REM 检查 Maven 是否安装
where mvn >nul 2>nul
if %errorlevel% equ 0 (
    echo [✓] 检测到 Maven，使用 Maven 运行...
    echo.
    mvn exec:java -Dexec.mainClass=CLSTelegraphCrawler
) else (
    echo [!] 未检测到 Maven，尝试手动编译运行...
    echo.
    
    REM 检查 Jsoup 是否存在
    if exist "jsoup-1.17.2.jar" (
        echo [✓] 找到 Jsoup 库，开始编译...
        javac -cp jsoup-1.17.2.jar -encoding UTF-8 src\CLSTelegraphCrawler.java
        
        if %errorlevel% equ 0 (
            echo [✓] 编译成功，开始运行...
            java -cp .;jsoup-1.17.2.jar -Dfile.encoding=UTF-8 CLSTelegraphCrawler
        ) else (
            echo [✗] 编译失败，请检查 Java 环境
            pause
            exit /b 1
        )
    ) else (
        echo [✗] 未找到 jsoup-1.17.2.jar
        echo.
        echo 请先到以下地址下载 Jsoup:
        echo https://jsoup.org/download
        echo.
        echo 然后将 jar 文件放到本目录后重新运行
        pause
        exit /b 1
    )
)

echo.
echo ========================================
echo   完成！
echo ========================================
pause
