$ErrorActionPreference = "Stop"
$root = "d:\Users\dddmiku\AppData\Local\红果免费短剧"
$work = "C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain"
foreach ($f in @("server.py","desktop_hls.py","desktop_hls_service.py")) {
  Copy-Item "$work\src\backend\$f" "$root\backend\$f" -Force
}
Copy-Item "$work\dist\hongguo-desktop-companion.exe" "$root\hongguo-desktop-companion.exe" -Force
Get-ChildItem "$root\backend\__pycache__" -Filter "*.pyc" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue
Write-Host ("exe     : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
Write-Host ("server  : " + (Get-FileHash "$root\backend\server.py" -Algorithm SHA256).Hash)
Write-Host ("hls     : " + (Get-FileHash "$root\backend\desktop_hls.py" -Algorithm SHA256).Hash)
Write-Host ("hlssvc  : " + (Get-FileHash "$root\backend\desktop_hls_service.py" -Algorithm SHA256).Hash)