param([string]$DatabasePath)
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'run.ps1') -CompileOnly
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $initArgs = @($projectRoot)
    if ($DatabasePath) { $initArgs += [System.IO.Path]::GetFullPath($DatabasePath) }
    & 'C:/Program Files/Java/jdk-26.0.1/bin/java.exe' --enable-native-access=ALL-UNNAMED -cp 'bin;.deps/jdbc/*' scripts/java/tools/CreateDatabase.java @initArgs
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
