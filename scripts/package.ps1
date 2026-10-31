param([switch]$Console)

# Builds dist/TestApp/TestApp.exe with a bundled Java runtime (jpackage app-image).
# -Console attaches a console window so startup errors and logs are visible.
$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$jdkBin = 'C:/Program Files/Java/jdk-26.0.1/bin'
$javaFxSdk = Join-Path $projectRoot '.deps/javafx-sdk-26.0.2'
$sqliteJar = Join-Path $projectRoot '.deps/jdbc/sqlite-jdbc-3.53.4.0.jar'
$staging = Join-Path $projectRoot '.deps/package'
$dist = Join-Path $projectRoot 'dist'
$appName = 'TestApp'
$appImage = Join-Path $dist $appName

# A running exe locks its runtime DLLs: replacing the image would leave it half-deleted.
$running = @(Get-Process -ErrorAction SilentlyContinue | Where-Object {
    $_.Path -and $_.Path.StartsWith("$appImage\", [System.StringComparison]::OrdinalIgnoreCase) })
if ($running.Count -gt 0) {
    throw "Close $appName (process $($running.Id -join ', ')) before packaging."
}

if (Test-Path -LiteralPath $staging) {
    Remove-Item -LiteralPath $staging -Recurse -Force
}
# classes: a build folder of its own; the IDE's Java builder also writes tests and scripts to bin/.
# input: jar files that jpackage puts on the class path.
# content: files next to the app that must stay off the class path.
$classesDir = Join-Path $staging 'classes'
$inputDir = Join-Path $staging 'input'
$contentDir = Join-Path $staging 'content'
$imageDir = Join-Path $staging 'image'

& (Join-Path $projectRoot 'run.ps1') -CompileOnly -OutputDir $classesDir
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

New-Item -ItemType Directory -Force -Path $inputDir, "$contentDir/config", "$contentDir/database", "$contentDir/javafx/lib", "$contentDir/javafx/bin" | Out-Null

& "$jdkBin/jar.exe" --create --file (Join-Path $inputDir 'app.jar') --main-class app.Main -C $classesDir .
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
Copy-Item -LiteralPath $sqliteJar -Destination $inputDir

# Read-only defaults: the database itself is created per user on first launch.
Copy-Item -LiteralPath (Join-Path $projectRoot 'config/database.properties') -Destination "$contentDir/config"
Copy-Item -LiteralPath (Join-Path $projectRoot 'database/schema.sql') -Destination "$contentDir/database"

# JavaFX keeps the SDK layout (lib/*.jar + bin/*.dll), where it looks for its native libraries.
$javaFxJars = @('javafx.base', 'javafx.graphics', 'javafx.controls', 'javafx.fxml' | ForEach-Object { Join-Path $javaFxSdk "lib/$_.jar" })
foreach ($jar in $javaFxJars) {
    Copy-Item -LiteralPath $jar -Destination "$contentDir/javafx/lib"
}
Copy-Item -LiteralPath (Join-Path $javaFxSdk 'lib/javafx.properties') -Destination "$contentDir/javafx/lib"
# WebView and media are not used by the app.
$unused = 'jfxwebkit.dll', 'jfxmedia.dll', 'gstreamer-lite.dll', 'fxplugins.dll', 'glib-lite.dll'
Get-ChildItem -LiteralPath (Join-Path $javaFxSdk 'bin') -Filter '*.dll' |
    Where-Object { $unused -notcontains $_.Name } |
    Copy-Item -Destination "$contentDir/javafx/bin"

# JDK modules for the bundled runtime are computed by jdeps from the app, SQLite JDBC and JavaFX,
# so a new JDK dependency in the code cannot go missing only in the packaged exe.
$javaFxLib = Join-Path $javaFxSdk 'lib'
$detected = @(& "$jdkBin/jdeps.exe" --ignore-missing-deps --multi-release 26 --print-module-deps --module-path $javaFxLib (Join-Path $inputDir 'app.jar') $sqliteJar)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
$detected += @(& "$jdkBin/jdeps.exe" --ignore-missing-deps --print-module-deps --module-path $javaFxLib @javaFxJars)
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }
# jdk.charsets: console code pages for -Console builds; jdeps cannot see this runtime need.
$modules = (@(($detected -join ',').Split(',') | ForEach-Object { $_.Trim() } | Where-Object { $_ -match '^(java|jdk)\.[\w.]+$' }) +
    'jdk.charsets' | Sort-Object -Unique) -join ','
Write-Output "Runtime modules: $modules"
# --app-content lands in the image root, which the launcher expands as $ROOTDIR.
# Single quotes keep the token literal here.
$javaOptions = @(
    '-Dapp.root=$ROOTDIR',
    '--module-path=$ROOTDIR\javafx\lib',
    '--add-modules=javafx.controls,javafx.fxml',
    '--enable-native-access=javafx.graphics,ALL-UNNAMED',
    '-Djava.library.path=$ROOTDIR\javafx\bin'
)
$arguments = @(
    '--type', 'app-image',
    '--name', $appName,
    '--app-version', '1.0.0',
    '--input', $inputDir,
    '--main-jar', 'app.jar',
    '--main-class', 'app.Main',
    '--add-modules', $modules,
    '--app-content', "$contentDir/config,$contentDir/database,$contentDir/javafx",
    '--dest', $imageDir
)
foreach ($option in $javaOptions) {
    $arguments += '--java-options', $option
}
if ($Console) {
    $arguments += '--win-console'
}
& "$jdkBin/jpackage.exe" @arguments
if ($LASTEXITCODE -ne 0) { exit $LASTEXITCODE }

# The previous image is replaced only after jpackage succeeded.
if (Test-Path -LiteralPath $appImage) {
    Remove-Item -LiteralPath $appImage -Recurse -Force
}
New-Item -ItemType Directory -Force -Path $dist | Out-Null
Move-Item -LiteralPath (Join-Path $imageDir $appName) -Destination $appImage
Write-Output "Application image: $(Join-Path $appImage "$appName.exe")"
