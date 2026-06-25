$src = 'd:\Program\leeks\build\distributions\leeks-3.3.44.zip'
$destDir = 'D:\WebStorm\plugins'

Write-Host "Installing plugin from $src to $destDir"
if (-not (Test-Path $src)) { Write-Host "Source not found: $src"; exit 1 }
if (-not (Test-Path $destDir)) { Write-Host "Destination plugins dir not found: $destDir"; exit 2 }

$destZip = Join-Path $destDir (Split-Path $src -Leaf)
$time = Get-Date -Format 'yyyyMMddHHmmss'

if (Test-Path $destZip) {
    $bak = Join-Path $destDir ("backup-zip-$time-" + (Split-Path $destZip -Leaf))
    Write-Host "Backing up existing zip to: $bak"
    Move-Item -Path $destZip -Destination $bak -Force
}

$destFolder = Join-Path $destDir ([io.path]::GetFileNameWithoutExtension($destZip))
if (Test-Path $destFolder) {
    $bakf = Join-Path $destDir ("backup-folder-$time-" + (Split-Path $destFolder -Leaf))
    Write-Host "Backing up existing folder to: $bakf"
    Move-Item -Path $destFolder -Destination $bakf -Force
}

Write-Host "Copying zip..."
Copy-Item -Path $src -Destination $destZip -Force
Write-Host "Copied zip to $destZip"

try {
    Write-Host "Extracting zip to folder $destFolder"
    if (Test-Path $destFolder) { Remove-Item -Path $destFolder -Recurse -Force }
    Expand-Archive -Path $destZip -DestinationPath $destFolder -Force
    Write-Host "Extracted to $destFolder"
} catch {
    Write-Host "Expand-Archive failed: $($_.Exception.Message)"
}

# Restart WebStorm
$proc = Get-Process -Name webstorm64 -ErrorAction SilentlyContinue
if ($proc) {
    Write-Host "Stopping WebStorm (webstorm64)..."
    try { Stop-Process -Id $proc.Id -Force } catch { Write-Host "Stop-Process failed: $($_.Exception.Message)" }
    Start-Sleep -Seconds 2
} else {
    Write-Host "WebStorm not running (webstorm64 process not found)."
}

$exe = 'D:\WebStorm\bin\webstorm64.exe'
if (Test-Path $exe) {
    Write-Host "Starting WebStorm: $exe"
    Start-Process -FilePath $exe -WorkingDirectory 'D:\WebStorm\bin'
    Write-Host "WebStorm started"
} else {
    Write-Host "WebStorm executable not found: $exe"
}
