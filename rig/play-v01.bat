@echo off
rem SOSDET V0.1: the owner's session (docs\release\V0.1.md). Three copies of the game on the Mogus-72 city, no
rem invasion; copy 1 opens in a normal window at 1x as the host. Close that window to end.
rem Usage: rig\play-v01.bat [run name, default V01A] [more rig.py arguments]
setlocal
cd /d "%~dp0.."
set TAG=%1
if "%TAG%"=="" set TAG=V01A
shift
set MORE=
:more
if "%1"=="" goto run
set MORE=%MORE% %1
shift
goto more
:run
call gradlew.bat assemble --console=plain -q
if errorlevel 1 exit /b 1
python rig\rig.py city --play --no-invade --copies 3 --tag %TAG% --save Mogus-72-19f86f1e048-47002c-0-121c.save %MORE%
echo.
echo The run's result: build\rig\runs\%TAG%\summary.txt
