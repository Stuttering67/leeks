# 自动构建脚本：在源码变更后自动运行 gradlew buildPlugin
# 使用方法：在项目根目录运行本脚本，或由外部终端以管理员/普通权限启动。
# 说明：会排除 build/.git/.idea 等目录，并使用 2s 防抖避免重复触发。

$ScriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$ProjectDir = Split-Path -Parent $ScriptDir
$GradleCmd = Join-Path $ProjectDir "gradlew.bat"
$DebounceMs = 2000

# 排除路径（正则）
$ExcludeRegex = '(^.*\\build\\.*$)|(^.*\\.git\\.*$)|(^.*\\.idea\\.*$)|(^.*\\build\\distributions\\.*$)|(^.*\\out\\.*$)|(^.*\\tmp\\.*$)'

# 全局绑定，供事件处理器访问
$Global:GradleCmd = $GradleCmd
$Global:ExcludeRegex = $ExcludeRegex

Write-Host "Auto-build watcher starting in: $ProjectDir"
Write-Host "Gradle wrapper: $GradleCmd"
Write-Host "Debounce: ${DebounceMs}ms"

# 创建防抖定时器
$timer = New-Object System.Timers.Timer($DebounceMs)
$timer.AutoReset = $false
$Global:timer = $timer

Register-ObjectEvent -InputObject $timer -EventName Elapsed -SourceIdentifier BuildTimerElapsed -Action {
    Write-Host "$(Get-Date -Format 'HH:mm:ss') - Debounce elapsed: running buildPlugin..."
    try {
        & $Global:GradleCmd buildPlugin --console=plain
        if ($LASTEXITCODE -eq 0) {
            Write-Host "$(Get-Date -Format 'HH:mm:ss') - buildPlugin completed successfully."
        } else {
            Write-Host "$(Get-Date -Format 'HH:mm:ss') - buildPlugin failed with exit code $LASTEXITCODE"
        }
    } catch {
        Write-Host "$(Get-Date -Format 'HH:mm:ss') - buildPlugin raised an error: $_"
    }
}

# 创建文件系统监视器
$watcher = New-Object System.IO.FileSystemWatcher
$watcher.Path = $ProjectDir
$watcher.IncludeSubdirectories = $true
$watcher.Filter = '*.*'
$watcher.NotifyFilter = [System.IO.NotifyFilters]'FileName, LastWrite, LastAccess, CreationTime, DirectoryName'
$watcher.EnableRaisingEvents = $true

# 事件处理器（共享同一脚本块）
$onChange = {
    $path = $Event.SourceEventArgs.FullPath
    if ($path -match $Global:ExcludeRegex) { return }
    Write-Host "$(Get-Date -Format 'HH:mm:ss') - Change detected: $path"
    try {
        $Global:timer.Stop()
        $Global:timer.Start()
    } catch {
        Write-Host "Error restarting timer: $_"
    }
}

Register-ObjectEvent -InputObject $watcher -EventName Changed -SourceIdentifier FileChanged -Action $onChange
Register-ObjectEvent -InputObject $watcher -EventName Created -SourceIdentifier FileCreated -Action $onChange
Register-ObjectEvent -InputObject $watcher -EventName Deleted -SourceIdentifier FileDeleted -Action $onChange
Register-ObjectEvent -InputObject $watcher -EventName Renamed -SourceIdentifier FileRenamed -Action $onChange

Write-Host "Watching for file changes under $ProjectDir (excludes build/, .git/, .idea/...). Press Ctrl+C to stop."

try {
    while ($true) { Start-Sleep -Seconds 1 }
} finally {
    Unregister-Event -SourceIdentifier FileChanged -ErrorAction SilentlyContinue
    Unregister-Event -SourceIdentifier FileCreated -ErrorAction SilentlyContinue
    Unregister-Event -SourceIdentifier FileDeleted -ErrorAction SilentlyContinue
    Unregister-Event -SourceIdentifier FileRenamed -ErrorAction SilentlyContinue
    Unregister-Event -SourceIdentifier BuildTimerElapsed -ErrorAction SilentlyContinue
    if ($watcher) { $watcher.Dispose() }
    if ($timer) { $timer.Dispose() }
}
