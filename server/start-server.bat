@echo off
REM Axzora family sync server - double-click to run.
title Axzora Sync Server
cd /d "%~dp0"

where node >nul 2>nul
if errorlevel 1 (
  echo Node.js was not found on PATH. Install it from https://nodejs.org
  pause
  exit /b 1
)

REM The admin code comes from the environment. .env.local is the local copy and
REM is gitignored; lines starting with # are comments. Without it the server
REM still runs, but the admin console stays closed (503 admin not configured).
if exist ".env.local" (
  for /f "usebackq eol=# tokens=1,* delims==" %%A in (".env.local") do (
    if not "%%A"=="" set "%%A=%%B"
  )
  echo Loaded .env.local
) else (
  echo WARNING: .env.local not found - the admin console will be closed.
)

echo Starting Axzora sync server...
echo Leave this window OPEN while you use location sharing.
echo.
node server.js
pause
