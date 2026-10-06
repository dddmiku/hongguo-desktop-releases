$ErrorActionPreference = "Stop"
$root = "d:\Users\dddmiku\AppData\Local\红果免费短剧"
$bak  = "C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\_backup\orig"

Get-Process hongguo-desktop-companion,msedgewebview2 -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

Copy-Item "$bak\hongguo-desktop-companion.exe" "$root\hongguo-desktop-companion.exe" -Force
Copy-Item "$bak\backend\server.py" "$root\backend\server.py" -Force
Copy-Item "$bak\backend\desktop_hls_service.py" "$root\backend\desktop_hls_service.py" -Force
Get-ChildItem "$root\backend\__pycache__" -Filter "*.pyc" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host ("exe                : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
Write-Host ("server.py          : " + (Get-FileHash "$root\backend\server.py" -Algorithm SHA256).Hash)
Write-Host ("desktop_hls_service: " + (Get-FileHash "$root\backend\desktop_hls_service.py" -Algorithm SHA256).Hash)