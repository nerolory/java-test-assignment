$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
Push-Location $projectRoot
try {
    & (Join-Path $projectRoot 'run.ps1') -CompileOnly
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    $jdkBin = 'C:/Program Files/Java/jdk-26.0.1/bin'
    & "$jdkBin/javac.exe" -encoding UTF-8 -cp 'bin;.deps/jdbc/*' -d '.deps/test-classes' tests/core/LoadProbe.java
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
    & "$jdkBin/java.exe" --enable-native-access=ALL-UNNAMED -Xmx256m -cp '.deps/test-classes;bin;.deps/jdbc/*' tests.core.LoadProbe
    exit $LASTEXITCODE
} finally {
    Pop-Location
}
