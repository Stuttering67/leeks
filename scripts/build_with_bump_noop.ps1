# Wrapper script that invokes gradlew which already increments version
# Keep a minimal helper for Windows users
Push-Location $PSScriptRoot\..\
try {
    Write-Host "Invoking gradlew to increment plugin patch version and build plugin..."
    & .\gradlew.bat clean buildPlugin
    if ($LASTEXITCODE -ne 0) { Write-Error "Gradle build failed with code $LASTEXITCODE"; exit $LASTEXITCODE }
    Write-Host "Build completed. See build/distributions for the zip." 
} finally { Pop-Location }
