$ErrorActionPreference = "Stop"
$Version = "8.9"
$ExpectedSha = "d725d707bfabd4dfdc958c624003b3c80accc03f7037b5122c4b1d0ef15cecab"
$GradleHome = if ($env:GRADLE_USER_HOME) { $env:GRADLE_USER_HOME } else { Join-Path $env:USERPROFILE ".gradle" }
$CacheRoot = Join-Path $GradleHome "mds-bootstrap"
$InstallDir = Join-Path $CacheRoot "gradle-$Version"
$ZipFile = Join-Path $CacheRoot "gradle-$Version-bin.zip"
$Url = "https://services.gradle.org/distributions/gradle-$Version-bin.zip"
$GradleBat = Join-Path $InstallDir "bin\gradle.bat"

if (-not (Test-Path $GradleBat)) {
    New-Item -ItemType Directory -Force -Path $CacheRoot | Out-Null
    if (-not (Test-Path $ZipFile)) {
        Write-Host "Descargando Gradle $Version..."
        Invoke-WebRequest -UseBasicParsing -Uri $Url -OutFile $ZipFile
    }

    $ActualSha = (Get-FileHash -Algorithm SHA256 -Path $ZipFile).Hash.ToLowerInvariant()
    if ($ActualSha -ne $ExpectedSha) {
        Remove-Item -Force -ErrorAction SilentlyContinue $ZipFile
        throw "Checksum de Gradle incorrecto. Se ha eliminado la descarga."
    }

    $TempDir = Join-Path $CacheRoot ("unpack-" + [Guid]::NewGuid().ToString("N"))
    New-Item -ItemType Directory -Force -Path $TempDir | Out-Null
    Expand-Archive -Path $ZipFile -DestinationPath $TempDir -Force
    if (Test-Path $InstallDir) { Remove-Item -Recurse -Force $InstallDir }
    Move-Item -Path (Join-Path $TempDir "gradle-$Version") -Destination $InstallDir
    Remove-Item -Recurse -Force $TempDir
}

& $GradleBat @args
exit $LASTEXITCODE
