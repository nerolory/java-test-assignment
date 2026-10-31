$ErrorActionPreference = 'Stop'
$projectRoot = Split-Path $PSScriptRoot -Parent
$destination = Join-Path $projectRoot '.deps/jdbc/sqlite-jdbc-3.53.4.0.jar'
$expectedHash = 'BCB1F51E36F940867E83342F9EFBF5968AC44A6BEF4D397BB4AF7B17B45CD2FB'
New-Item -ItemType Directory -Force -Path (Split-Path $destination) | Out-Null
if (-not (Test-Path -LiteralPath $destination)) {
    Invoke-WebRequest 'https://repo.maven.apache.org/maven2/org/xerial/sqlite-jdbc/3.53.4.0/sqlite-jdbc-3.53.4.0.jar' -OutFile $destination
}
if ((Get-FileHash -LiteralPath $destination -Algorithm SHA256).Hash -ne $expectedHash) {
    throw 'SQLite JDBC checksum mismatch. The dependency must not be used.'
}
Write-Output 'SQLite JDBC 3.53.4.0 verified.'
