$ErrorActionPreference = "Stop"
$root = "d:\Users\dddmiku\AppData\Local\红果免费短剧"
$work = "C:\Users\dddmiku\Desktop\codex\hongguo-desktop-maintain"

# 需要同步的后端文件：补丁涉及的全部文件。缺一个就会出现「媒体准备失败」。
$files = @("server.py", "desktop_hls.py", "desktop_hls_service.py", "desktop_encode.py")

Get-Process hongguo-desktop-companion -ErrorAction SilentlyContinue | Stop-Process -Force
Start-Sleep -Seconds 4

Write-Host "=== 部署前 ==="
Write-Host ("exe    : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
foreach ($f in $files) {
  Write-Host ("{0,-22}: {1}" -f $f, (Get-FileHash "$root\backend\$f" -Algorithm SHA256).Hash)
}

Copy-Item "$work\dist\hongguo-desktop-companion.exe" "$root\hongguo-desktop-companion.exe" -Force
foreach ($f in $files) {
  Copy-Item "$work\src\backend\$f" "$root\backend\$f" -Force
}
Get-ChildItem "$root\backend\__pycache__" -Filter "*.pyc" -ErrorAction SilentlyContinue | Remove-Item -Force -ErrorAction SilentlyContinue

Write-Host "=== 部署后 ==="
Write-Host ("exe    : " + (Get-FileHash "$root\hongguo-desktop-companion.exe" -Algorithm SHA256).Hash)
foreach ($f in $files) {
  Write-Host ("{0,-22}: {1}" -f $f, (Get-FileHash "$root\backend\$f" -Algorithm SHA256).Hash)
}
