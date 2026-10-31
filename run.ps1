# -OutputDir: build folder other than bin/ (scripts/package.ps1 uses one the IDE never writes to).
param([switch]$CompileOnly, [string]$OutputDir)

$ErrorActionPreference = 'Stop'
$javaFxLib = Join-Path $PSScriptRoot '.deps/javafx-sdk-26.0.2/lib'
$outputDir = if ($OutputDir) { [System.IO.Path]::GetFullPath($OutputDir) } else { Join-Path $PSScriptRoot 'bin' }
$sourceDir = Join-Path $PSScriptRoot 'app'
$jdkBin = 'C:/Program Files/Java/jdk-26.0.1/bin'

$sassCli = Join-Path $PSScriptRoot 'node_modules/sass/sass.js'
if (-not (Test-Path -LiteralPath $sassCli)) {
    throw 'SCSS compiler is missing. Run npm.cmd ci first.'
}
& node (Join-Path $PSScriptRoot 'scripts/build-styles.cjs')
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

$jdbcDir = Join-Path $PSScriptRoot '.deps/jdbc'
$sqliteJar = Join-Path $jdbcDir 'sqlite-jdbc-3.53.4.0.jar'
if (-not (Test-Path -LiteralPath $sqliteJar)) {
    throw 'SQLite JDBC is missing. Run scripts/install-jdbc.ps1 first.'
}
# Build into a per-process staging folder, then mirror it into bin/. Files removed from the
# sources disappear from the classpath, while a running app or a parallel test script never
# sees bin/ missing or half-built: unchanged files stay in place and only stale ones are deleted.
$stagingDir = Join-Path $PSScriptRoot ".deps/build-$PID"
if (Test-Path -LiteralPath $stagingDir) {
    Remove-Item -LiteralPath $stagingDir -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $stagingDir | Out-Null
try {
    $sources = @(Get-ChildItem -LiteralPath $sourceDir -Filter '*.java' -Recurse | ForEach-Object FullName)
    $sources += @(Get-ChildItem -LiteralPath (Join-Path $PSScriptRoot 'core') -Filter '*.java' -Recurse | ForEach-Object FullName)
    & "$jdkBin/javac.exe" --module-path $javaFxLib --add-modules javafx.controls,javafx.fxml -cp "$jdbcDir/*" -d $stagingDir @sources
    if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

    $resourcesDir = Join-Path $PSScriptRoot 'resources'
    Copy-Item -LiteralPath (Join-Path $resourcesDir 'fxml') -Destination $stagingDir -Recurse -Force
    $assetsOutput = Join-Path $stagingDir 'assets'
    New-Item -ItemType Directory -Force -Path $assetsOutput | Out-Null
    Get-ChildItem -LiteralPath (Join-Path $resourcesDir 'assets') | Where-Object Name -ne 'scss' | Copy-Item -Destination $assetsOutput -Recurse -Force

    # robocopy exit codes 0-7 mean success (files copied or extras removed); 8 and above mean failure.
    & robocopy $stagingDir $outputDir /MIR /R:2 /W:1 /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { exit $LASTEXITCODE }
} finally {
    Remove-Item -LiteralPath $stagingDir -Recurse -Force -ErrorAction SilentlyContinue
}

if (-not $CompileOnly) {
    & "$jdkBin/java.exe" "-Dapp.root=$PSScriptRoot" --module-path $javaFxLib --add-modules javafx.controls,javafx.fxml --enable-native-access=javafx.graphics,ALL-UNNAMED -cp "$outputDir;$jdbcDir/*" app.Main
    exit $LASTEXITCODE
}
# robocopy leaves a non-zero success code in $LASTEXITCODE; callers check it after -CompileOnly.
exit 0
