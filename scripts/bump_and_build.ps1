param(
    [string]$message
)

$scriptDir = Split-Path -Parent $MyInvocation.MyCommand.Definition
$root = Resolve-Path (Join-Path $scriptDir "..")
$buildGradle = Join-Path $root 'build.gradle'
$pluginXml = Join-Path $root 'src\main\resources\META-INF\plugin.xml'

if (-not (Test-Path $buildGradle)) { Write-Error "build.gradle 未找到: $buildGradle"; exit 1 }

Write-Host "读取 $buildGradle"
$content = Get-Content $buildGradle -Raw
if ($content -match "version\s*['\"]([0-9]+)\.([0-9]+)\.([0-9]+)['\"]") {
    $major = $matches[1]; $minor = $matches[2]; $patch = [int]$matches[3]
    $newPatch = $patch + 1
    $newVersion = "$major.$minor.$newPatch"
    $newContent = $content -replace "version\s*['\"][0-9]+\.[0-9]+\.[0-9]+['\"]", "version '$newVersion'"
    Set-Content -Path $buildGradle -Value $newContent -Encoding UTF8
    Write-Host "已将版本从 $major.$minor.$patch 更新为 $newVersion"
} else {
    Write-Error "在 build.gradle 中未找到版本声明，无法自动更新。"
    exit 1
}

# 同步更新 plugin.xml 中的 <version>
if (-not (Test-Path $pluginXml)) { Write-Warning "plugin.xml 未找到: $pluginXml" } else {
    $p = Get-Content $pluginXml -Raw
    if ($p -match '<version>.*</version>') {
        $p2 = $p -replace '<version>.*</version>', "<version>$newVersion</version>"
    } else {
        # 插入到 <name> 标签之后
        $p2 = $p -replace '(?<=(<name>.*?</name>))', "`n    <version>$newVersion</version>"
    }
    Set-Content -Path $pluginXml -Value $p2 -Encoding UTF8
    Write-Host "plugin.xml 已写入显示版本 $newVersion"
}

# 运行构建
Push-Location $root
try {
    Write-Host "开始构建插件 (buildPlugin)..."
    & "${root}\gradlew.bat" buildPlugin
} catch {
    Write-Error "构建失败: $_"
    Pop-Location
    exit 1
}

# 提交到 git
if ([string]::IsNullOrWhiteSpace($message)) {
    $message = "chore: auto build $(Get-Date -Format yyyy-MM-dd_HH:mm:ss)"
}
try {
    & git add -A
    & git commit -m $message
    Write-Host "已提交到本地 git： $message"
} catch {
    Write-Warning "git 提交失败或无变更: $_"
}

# 查找生成的分发包
$dist = Join-Path $root 'build\distributions'
if (Test-Path $dist) {
    $zips = Get-ChildItem -Path $dist -Filter *.zip | Sort-Object LastWriteTime -Descending
    if ($zips.Length -gt 0) {
        Write-Host "生成的插件：" $zips[0].FullName
    } else { Write-Warning "未在 build/distributions 下找到 zip 包" }
} else { Write-Warning "未找到 build/distributions 目录" }
Pop-Location
