@echo off
chcp 936
cd %~dp0source_SingleThread%
..\jre\jre\bin\java ImageMoving
pause