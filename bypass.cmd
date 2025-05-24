REM Save this as run‑bypass.cmd next to your .ps1
@echo off
REM Launch PowerShell without loading your profile and ignore the policy
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "build.ps1" %*

@REM Set-ExecutionPolicy Unrestricted -Scope Process -Force

WARNING: A restricted method in java.lang.System has been called
WARNING: java.lang.System::load has been called by com.sun.glass.utils.NativeLibLoader in module javafx.graphics (file:/C:/Users/kkasdasd/.m2/repository/org/openjfx/javafx-graphics/20/javafx-graphics-20-win.jar)
WARNING: Use --enable-native-access=javafx.graphics to avoid a warning for callers in this module