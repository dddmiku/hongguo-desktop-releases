$ErrorActionPreference = "Stop"
$root = "d:\Users\dddmiku\AppData\Local\红果免费短剧"
$bak  = "C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain\_backup\orig"
$files = @("server.py", "desktop_hls.py", "desktop_hls_service.py", "desktop_encode.py")

Get-Process hongguo-desktop-companion,msedgewebview2 -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

Copy-Item "$bak\hongguo-desktop-companion.exe" "$root\hongguo-desktop-companion.exe" -Force
foreach ($f in $files) {
  if (Test-Path "$bak\backend\$f") {
    Copy-Item "$bak\backend\$f" "$root\backend\$f" -Force
  }
}
Get-ChildItem "$root\backend\__pycache__" -Filter "*.pyc" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host ("exe                : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
foreach ($f in $files) {
  Write-Host ("{0,-22}: {1}" -f $f, (Get-FileHash "$root\backend\$f" -Algorithm SHA256).Hash)
}
