# Bump plugin patch version and build plugin
# Usage: .\build_with_bump.ps1

$wd = Split-Path -Path $MyInvocation.MyCommand.Definition -Parent
Push-Location $PSScriptRoot\..\
try {
    Write-Host "Running Gradle to increment version and build plugin..."
    & .\gradlew.bat clean buildPlugin
    if ($LASTEXITCODE -ne 0) { Write-Error "Gradle build failed with code $LASTEXITCODE"; exit $LASTEXITCODE }
    Write-Host "Build completed. Artifact under build/distributions/"
} finally {
    Pop-Location
}
