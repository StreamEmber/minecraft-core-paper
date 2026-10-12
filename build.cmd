@echo off
rem Cift tikla ya da: build -NoCopy
powershell -NoProfile -ExecutionPolicy Bypass -File "%~dp0build.ps1" %*
