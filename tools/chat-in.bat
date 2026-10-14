@echo off
setlocal
cd /d "%~dp0"

where pythonw >nul 2>nul
if %errorlevel%==0 (
    start "" pythonw "%~dp0chat-in.py"
    exit /b 0
)

where python >nul 2>nul
if %errorlevel%==0 (
    start "" python "%~dp0chat-in.py"
    exit /b 0
)

echo 没找到 Python。装一个 Python 3，或者改用「语音输入」方式。
pause
