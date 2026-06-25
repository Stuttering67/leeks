# Bump plugin patch version and build plugin
# Usage: .\build_and_bump.ps1

Push-Location $PSScriptRoot\..\
try {
    Write-Host "Running Gradle bumpAndBuildPlugin (will increment patch and build plugin zip)..."
    & .\gradlew.bat bumpAndBuildPlugin --console=plain
    if ($LASTEXITCODE -ne 0) { Write-Error "Gradle build failed with code $LASTEXITCODE"; exit $LASTEXITCODE }

    # Read updated version from build.gradle (first line that contains 'version')
    $versionLine = Get-Content -Path "build.gradle" | Where-Object { $_ -match '^[ \t]*version' } | Select-Object -First 1
    $newVersion = 'unknown'
    if ($versionLine -and ($versionLine -match '(\d+\.\d+\.\d+)')) { $newVersion = $Matches[1] }

    # Find generated zip under build/distributions
    $zip = Get-ChildItem -Path "build/distributions" -Filter "*.zip" -File | Sort-Object LastWriteTime -Descending | Select-Object -First 1
    if ($zip) {
        Write-Host "Build finished. Version: $newVersion"
        Write-Host "Plugin ZIP: $($zip.FullName)"
        exit 0
    } else {
        Write-Warning "No zip found under build/distributions. Version: $newVersion"
        exit 0
    }
} finally { Pop-Location }
