@echo off
setlocal EnableExtensions

rem ============================================================
rem  Resume Screening System - start supporting services
rem  Backend is NOT started here: run it in IntelliJ IDEA
rem  (Run ResumeScreeningApplication, port 8080).
rem
rem  ASCII only on purpose: keeps the console output readable
rem  regardless of the active code page.
rem ============================================================

set "ROOT=%~dp0"
set "JAVA_HOME=C:\download\jdk\jdk17"
set "MQ_HOME=C:\download\rocketmq-all-5.3.3-bin-release"
set "PY=C:\Users\ZJQ\.conda\envs\ai\python.exe"
set "JAVA=%JAVA_HOME%\bin\java.exe"
set "MQ_CP=.;%MQ_HOME%\conf;%MQ_HOME%\lib\*"

echo ============================================================
echo   Resume Screening System - start services
echo ============================================================
echo   NameServer   9876
echo   Broker      10911
echo   AI service   8000   (FastAPI + LangChain)
echo   Frontend     5173   (Vite)
echo   Backend      8080   -- start it in IDEA, not here
echo ============================================================
echo.

if not exist "%JAVA%" (
  echo [ERROR] JDK 17 not found: %JAVA%
  echo         Edit JAVA_HOME in this file.
  pause
  exit /b 1
)
if not exist "%PY%" (
  echo [ERROR] conda env python not found: %PY%
  echo         Edit PY in this file.
  pause
  exit /b 1
)
if not exist "%MQ_HOME%\bin" (
  echo [ERROR] RocketMQ not found: %MQ_HOME%
  echo         Edit MQ_HOME in this file.
  pause
  exit /b 1
)

rem ------------------------------------------------------------ 1/4 NameServer
call :port_busy 9876
if not errorlevel 1 (
  echo [1/4] SKIP  NameServer already listening on 9876
) else (
  echo [1/4] START NameServer ...
  start "RS-MQ-NameServer" cmd /k "cd /d %MQ_HOME% && %JAVA% -server -Xms512m -Xmx512m -Xmn256m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m -Drocketmq.home.dir=%MQ_HOME% -cp %MQ_CP% org.apache.rocketmq.namesrv.NamesrvStartup"
  timeout /t 6 /nobreak >nul
)

rem --------------------------------------------------------------- 2/4 Broker
call :port_busy 10911
if not errorlevel 1 (
  echo [2/4] SKIP  Broker already listening on 10911
) else (
  echo [2/4] START Broker ...
  start "RS-MQ-Broker" cmd /k "cd /d %MQ_HOME% && %JAVA% -server -Xms512m -Xmx512m -XX:MetaspaceSize=128m -XX:MaxMetaspaceSize=256m -Drocketmq.home.dir=%MQ_HOME% -cp %MQ_CP% org.apache.rocketmq.broker.BrokerStartup -c %MQ_HOME%\conf\broker-local.conf"
  timeout /t 6 /nobreak >nul
)

rem ----------------------------------------------------------- 3/4 AI service
call :port_busy 8000
if not errorlevel 1 (
  echo [3/4] SKIP  AI service already listening on 8000
) else (
  echo [3/4] START AI service ...
  start "RS-AI-Service" cmd /k "cd /d %ROOT%ai-service && %PY% run.py"
  timeout /t 5 /nobreak >nul
)

rem ------------------------------------------------------------- 4/4 Frontend
if not exist "%ROOT%frontend\node_modules" (
  echo        node_modules missing, running npm install ...
  pushd "%ROOT%frontend"
  call npm install --registry=https://registry.npmmirror.com
  popd
)
call :port_busy 5173
if not errorlevel 1 (
  echo [4/4] SKIP  Frontend already listening on 5173
) else (
  echo [4/4] START Frontend ...
  start "RS-Frontend" cmd /k "cd /d %ROOT%frontend && npm run dev"
)

echo.
echo ============================================================
echo   Done. Four console windows should be open.
echo.
echo   NEXT STEP - start the backend in IDEA:
echo     1. Open  backend  folder as a Maven project
echo     2. Project SDK = JDK 17, Lombok annotation processing on
echo     3. Run   com.resume.screening.ResumeScreeningApplication
echo     4. Wait until "Tomcat started on port 8080"
echo.
echo   Then open  http://127.0.0.1:5173
echo.
echo   Stop everything: stop-all.bat  (backend: press Stop in IDEA)
echo ============================================================
echo.
pause
exit /b 0

rem --------------------------------------------------------- helper :port_busy
rem  errorlevel 0 = port already LISTENING, 1 = free
:port_busy
netstat -an | findstr /C:"LISTENING" | findstr /C:":%~1 " >nul 2>&1
exit /b %errorlevel%
