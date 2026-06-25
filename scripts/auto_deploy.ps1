param(
    [switch]$Push,
    [string]$WebStormPath = "D:\\WebStorm",
    [string]$Gradle = ".\\gradlew.bat",
    [string]$CommitMessage = "",
    [switch]$ForceRestart
)

$ErrorActionPreference = 'Stop'
try {
    # 脚本位置 -> 项目根目录
    $RepoRoot = Resolve-Path (Join-Path $PSScriptRoot '..') | Select-Object -ExpandProperty Path
    Write-Output "RepoRoot: $RepoRoot"
    Set-Location $RepoRoot

    Write-Output "==> Running Gradle build (clean buildPlugin)..."
    & $Gradle clean buildPlugin
    if ($LASTEXITCODE -ne 0) { Write-Error "Gradle build failed (exit code $LASTEXITCODE)"; exit $LASTEXITCODE }

    $dist = Get-ChildItem -Path "$RepoRoot\build\distributions" -Filter "*.zip" | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if (-not $dist) { Write-Error "No distribution zip found under build/distributions"; exit 2 }
    Write-Output "Found distribution: $($dist.FullName)"

    # 复制到 WebStorm 插件目录并解压
    $pluginsDir = Join-Path $WebStormPath 'plugins'
    New-Item -ItemType Directory -Path $pluginsDir -Force | Out-Null
    Copy-Item -Path $dist.FullName -Destination $pluginsDir -Force
    $destZip = Join-Path $pluginsDir $dist.Name
    $destDir = Join-Path $pluginsDir $dist.BaseName
    if (Test-Path $destDir) { Remove-Item -Recurse -Force $destDir }
    Expand-Archive -Path $destZip -DestinationPath $destDir -Force
    Write-Output "Copied and expanded to $destDir"

    # git commit (如果是 git 仓库)
    if (git rev-parse --is-inside-work-tree 2>$null) {
        $status = git status --porcelain
        if ($status) {
            $msg = if ($CommitMessage) { $CommitMessage } else { "chore: auto build $(Get-Date -Format 'yyyy-MM-dd_HH:mm:ss')" }
            git add -A
            git commit -m $msg
            Write-Output "Committed changes: $msg"
        } else {
            Write-Output "No changes to commit"
        }
        if ($Push) {
            try {
                $branch = git rev-parse --abbrev-ref HEAD
                git push origin $branch
                Write-Output "Pushed to origin/$branch"
            } catch {
                Write-Warning "Git push failed: $($_.Exception.Message) - continuing without push"
            }
        }
    } else {
        Write-Output "Not a git repository; skipping commit/push"
    }

    # 启动/重启 WebStorm (默认: 若发现已在运行则不强制重启以避免 DirectoryLock 错误)
    Write-Output "Checking WebStorm at $WebStormPath ..."
    $procs = Get-Process -Name webstorm64,webstorm,idea64 -ErrorAction SilentlyContinue
    if ($procs) {
        if ($ForceRestart) {
            Write-Output "Force restarting WebStorm at $WebStormPath ..."
            $procs | Stop-Process -Force -ErrorAction SilentlyContinue
            Start-Sleep -Seconds 1
            $exe = Join-Path $WebStormPath 'bin\webstorm64.exe'
            if (-not (Test-Path $exe)) { Write-Error "WebStorm executable not found at $exe"; exit 3 }
            Start-Process -FilePath $exe -WorkingDirectory $WebStormPath -WindowStyle Normal
            Write-Output "AUTO_DEPLOY_DONE"
        } else {
            Write-Warning "WebStorm appears to be running; skipping automatic restart to avoid DirectoryLock. Use -ForceRestart to force it."
            Write-Output "AUTO_DEPLOY_DONE"
        }
    } else {
        Write-Output "Starting WebStorm at $WebStormPath ..."
        $exe = Join-Path $WebStormPath 'bin\webstorm64.exe'
        if (-not (Test-Path $exe)) { Write-Error "WebStorm executable not found at $exe"; exit 3 }
        Start-Process -FilePath $exe -WorkingDirectory $WebStormPath -WindowStyle Normal
        Write-Output "AUTO_DEPLOY_DONE"
    }
} catch {
    Write-Error "AUTO_DEPLOY_FAILED: $($_.Exception.Message)"
    exit 1
}