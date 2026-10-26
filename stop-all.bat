@echo off
setlocal EnableExtensions

rem ============================================================
rem  Stop the services started by start-all.bat
rem  The backend runs in IDEA - stop it there (port 8080 is NOT
rem  killed here on purpose, so your IDEA session survives).
rem ============================================================

echo ============================================================
echo   Stop services
echo ============================================================
echo.

for %%T in ("RS-Frontend" "RS-AI-Service" "RS-MQ-Broker" "RS-MQ-NameServer") do (
  taskkill /F /T /FI "WINDOWTITLE eq %%~T" >nul 2>&1
  if errorlevel 1 (
    echo   [SKIP] %%~T  not running
  ) else (
    echo   [KILL] %%~T
  )
)

echo.
echo   Cleanup by port ...
for %%P in (5173 8000 10911 9876) do (
  for /f "tokens=5" %%A in ('netstat -ano ^| findstr "LISTENING" ^| findstr ":%%P "') do (
    taskkill /F /PID %%A >nul 2>&1 && echo   [KILL] port %%P  PID %%A
  )
)

echo.
echo   Backend on 8080 is managed by IDEA - press Stop there.
echo   All other services are stopped.
echo.
timeout /t 3 /nobreak >nul
