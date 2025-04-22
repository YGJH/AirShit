: #!/usr/bin/env bash
# ————————————————————— Bash / WSL section ——————————————————————
ACTION="$1"
case "$ACTION" in
  push)
    clear
    git add .
    git commit -m "auto commit"
    git push
    ;;
  pull)
    clear
    git reset --hard
    git pull
    ;;
  test)
    rm -rf ../AirShitTest
    mkdir ../AirShitTest
    cp -r asset ../AirShitTest
    cp *.java ../AirShitTest
    javac -d . *.java
    javac -d ../AirShitTest ../AirShitTest/*.java
    java AirShit.Main
    ;;
  *)
    javac -d . *.java
    java AirShit.Main
    ;;
esac
exit

: windows
@echo off
cls
set "ACTION=%~1"
if /I "%ACTION%"=="push" (
  git add .
  git commit -m "auto commit"
  git push
) else if /I "%ACTION%"=="pull" (
  git reset --hard
  git pull
) else if /I "%ACTION%"=="test" (
  if exist ..\AirShitTest rmdir /s /q ..\AirShitTest
  mkdir ..\AirShitTest
  xcopy asset ..\AirShitTest\*.* /E /I /Y
  xcopy *.java ..\AirShitTest\*.* /Y
  javac -d . *.java
  javac -d ..\AirShitTest ..\AirShitTest\*.java
  java AirShit.Main
) else (
  javac -d . *.java
  java AirShit.Main
)


@REM Installation:

@REM Save the above into c:\Users\asd7766zxc\Documents\java_shit\AirShit\build
@REM In WSL:
@REM chmod +x build
@REM ./build push / ./build pull / ./build test / ./build
@REM In Windows CMD/PowerShell (from the same folder):
@REM build push / build pull / build test / build
