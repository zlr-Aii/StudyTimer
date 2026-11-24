# StudyTimer 1.0 packaging: produces dist\StudyTimer\StudyTimer.exe (app-image with bundled Java runtime)
# Needs JDK 21 (jpackage) and Maven. Optional: packaging\ffmpeg\ffmpeg.exe is bundled.
# VLC must still be installed on the target machine (vlcj uses system libVLC).
$ErrorActionPreference = 'Stop'
$root = Split-Path -Parent $PSScriptRoot
Set-Location $root

try {
    & mvn -B -q clean package -DskipTests
    if ($LASTEXITCODE -ne 0) { throw "mvn package failed" }

    $stageDir = Join-Path $root 'build\jpackage-input'
    if (Test-Path $stageDir) { Remove-Item -Recurse -Force $stageDir }
    New-Item -ItemType Directory -Force $stageDir | Out-Null
    Copy-Item 'target\study-timer-1.0.0.jar' $stageDir
    Copy-Item 'target\libs\*.jar' $stageDir

    # Build into build\ first, then mirror into dist\StudyTimer, so an Explorer window
    # or terminal open in dist\StudyTimer doesn't block the build.
    $outDir = Join-Path $root 'build\jpackage-out'
    if (Test-Path $outDir) { Remove-Item -Recurse -Force $outDir }

    & jpackage --type app-image `
        --name StudyTimer `
        --app-version 1.0.0 `
        --vendor StudyTimer `
        --icon packaging\StudyTimer.ico `
        --input $stageDir `
        --main-jar study-timer-1.0.0.jar `
        --main-class com.studytimer.Launcher `
        --java-options '-Dfile.encoding=UTF-8' `
        --dest $outDir
    if ($LASTEXITCODE -ne 0) { throw "jpackage failed" }

    $built = Join-Path $outDir 'StudyTimer'
    if (Test-Path 'packaging\ffmpeg\ffmpeg.exe') {
        New-Item -ItemType Directory -Force (Join-Path $built 'ffmpeg') | Out-Null
        Copy-Item 'packaging\ffmpeg\ffmpeg.exe' (Join-Path $built 'ffmpeg\')
    }

    New-Item -ItemType Directory -Force 'dist\StudyTimer' | Out-Null
    & robocopy $built 'dist\StudyTimer' /MIR /NFL /NDL /NJH /NJS /NP | Out-Null
    if ($LASTEXITCODE -ge 8) { throw "copy to dist failed (StudyTimer still running?)" }
    $global:LASTEXITCODE = 0

    Remove-Item -Recurse -Force $stageDir
    Remove-Item -Recurse -Force $outDir
    Write-Host "DONE: dist\StudyTimer\StudyTimer.exe"
}
finally {
    & git checkout -- target 2>$null
}
