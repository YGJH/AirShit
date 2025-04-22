REM Save this as run‑bypass.cmd next to your .ps1
@echo off
REM Launch PowerShell without loading your profile and ignore the policy
powershell.exe -NoProfile -ExecutionPolicy Bypass -File "build.ps1" %*