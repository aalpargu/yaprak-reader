param([ValidateSet('Sources','Originals','Pdf','Chrome','Reader','All')][string]$Group='All',[switch]$PreflightOnly)
$ErrorActionPreference='Stop'
$ownedRepo=Split-Path -Parent $PSScriptRoot
if(!(Test-Path -LiteralPath "$ownedRepo/docs/verification/candidate-results-0.175.0.json")){
    $ownedRepo='C:/Users/alpar/Documents/Codex/2026-09-19/i-want-to-build-a-personal/outputs/Yaprak'
}
$ownedAdb='C:/Users/alpar/Documents/Codex/2026-09-01/files-pasted-by-the-user-android/work/toolchain/sdk/platform-tools/adb.exe'
$ownedReport=Get-Content -LiteralPath "$ownedRepo/docs/verification/candidate-results-0.175.0.json" -Raw | ConvertFrom-Json
if($ownedReport.version -ne '0.175.0' -or $ownedReport.versionCode -ne 176 -or
    !$ownedReport.instrumentationApk.compiled -or $ownedReport.selectedDeviceTestCount -ne 52 -or $ownedReport.deviceTests.Count -ne 22){throw 'Unexpected candidate or selected test contract'}
foreach($ownedArtifact in @($ownedReport.apk,$ownedReport.instrumentationApk)){
    if((Get-FileHash -LiteralPath $ownedArtifact.delivery -Algorithm SHA256).Hash -ne $ownedArtifact.sha256){throw 'Host artifact mismatch'}
}
$ownedCases=@($ownedReport.deviceTests | Where-Object {$Group -eq 'All' -or $_.group -eq $Group})
$ownedExpected=[int](($ownedCases | Measure-Object -Property count -Sum).Sum)
if($ownedExpected -le 0 -or ($Group -eq 'All' -and $ownedExpected -ne 52)){throw 'Incorrect selected test count'}
$ownedDevices=@(& $ownedAdb devices -l 2>&1)
if($LASTEXITCODE -ne 0){throw 'ADB inventory failed'}
if(!($ownedDevices -match '^emulator-5570\s+device(?:\s|$)')){
    if($PreflightOnly){Write-Output 'Exact APK hashes and test contract verified; owned emulator unavailable. Nothing installed or executed.';exit 0}
    throw 'Start the owned emulator first; no installation attempted'
}
$ownedName=@(& $ownedAdb -s emulator-5570 emu avd name 2>&1)
if($LASTEXITCODE -ne 0 -or !($ownedName | Where-Object {([string]$_).Trim() -eq 'yaprak-source-api35'})){throw 'Only the owned emulator is allowed'}
$ownedBoot=(& $ownedAdb -s emulator-5570 shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
$ownedApi=(& $ownedAdb -s emulator-5570 shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
if($ownedBoot -ne '1' -or $ownedApi -ne '35'){throw 'The owned API35 emulator is not ready'}
$ownedAnr=(& $ownedAdb -s emulator-5570 logcat -b events -d -s am_anr 2>&1 | Out-String)
$ownedWindows=(& $ownedAdb -s emulator-5570 shell dumpsys window windows 2>&1 | Out-String)
if($LASTEXITCODE -ne 0 -or $ownedAnr -match 'am_anr' -or $ownedWindows -match 'Application Not Responding'){throw 'Owned emulator has ANR evidence; no test started'}
if($PreflightOnly){Write-Output 'Exact APK hashes, test contract and ready owned emulator verified; nothing installed or executed.';exit 0}
$ownedProof="$ownedRepo/docs/verification/pdf-deferred-placement-0.175.0"
$ownedStamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$ownedLog="$ownedProof/device-$($Group.ToLowerInvariant())-$ownedStamp.log"
@("Actual owned Android35 group $Group; UTC $([DateTimeOffset]::UtcNow.ToString('o'))",
    "Main SHA256 $($ownedReport.apk.sha256)","Test SHA256 $($ownedReport.instrumentationApk.sha256)") |
    Set-Content -LiteralPath $ownedLog -Encoding utf8NoBOM
foreach($ownedArtifact in @($ownedReport.apk,$ownedReport.instrumentationApk)){
    $ownedInstall=@(& $ownedAdb -s emulator-5570 install -r $ownedArtifact.delivery 2>&1)
    $ownedExit=$LASTEXITCODE
    $ownedInstall | Add-Content -LiteralPath $ownedLog -Encoding utf8NoBOM
    if($ownedExit -ne 0 -or !($ownedInstall -match '^Success\s*$')){throw "Install not confirmed; see $ownedLog"}
}
$ownedPackage=(& $ownedAdb -s emulator-5570 shell dumpsys package com.yaprak.reader 2>&1 | Out-String)
if($LASTEXITCODE -ne 0 -or $ownedPackage -notmatch 'versionCode=176\b' -or $ownedPackage -notmatch 'versionName=0\.175\.0\b'){throw 'Installed version mismatch'}
$ownedNames=($ownedCases | ForEach-Object {$_.class}) -join ','
$ownedNames | Add-Content -LiteralPath $ownedLog -Encoding utf8NoBOM
$ownedOutput=@(& $ownedAdb -s emulator-5570 shell am instrument -w -r -e class $ownedNames 'com.yaprak.reader.test/androidx.test.runner.AndroidJUnitRunner' 2>&1)
$ownedExit=$LASTEXITCODE
$ownedOutput | Add-Content -LiteralPath $ownedLog -Encoding utf8NoBOM
$ownedText=$ownedOutput -join "`n"
$ownedAnrAfter=@(& $ownedAdb -s emulator-5570 logcat -b events -d -s am_anr 2>&1)
@('Actual am_anr events after owned fixture run (empty below means none):') + $ownedAnrAfter | Set-Content -LiteralPath "$ownedProof/system-anr-after-$($Group.ToLowerInvariant())-$ownedStamp.txt" -Encoding utf8NoBOM
$ownedAnrFree=$LASTEXITCODE -eq 0 -and !($ownedAnrAfter -match 'am_anr')
$ownedCompleted=([regex]::Matches($ownedText,'(?m)^INSTRUMENTATION_STATUS_CODE: 0\s*$')).Count
$ownedPassed=$ownedAnrFree -and $ownedExit -eq 0 -and $ownedText -match "(?m)^OK \($ownedExpected tests?\)\s*$" -and $ownedCompleted -eq $ownedExpected -and
    $ownedText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]'
[ordered]@{version='0.175.0';group=$Group;serial='emulator-5570';avd='yaprak-source-api35';api=35;expected=$ownedExpected;
    emulatorAnrFree=$ownedAnrFree;completedPassed=$ownedCompleted;classes=$ownedCases;passed=$ownedPassed;adbExit=$ownedExit;log=(Split-Path -Leaf $ownedLog);
    mainSha256=$ownedReport.apk.sha256;testSha256=$ownedReport.instrumentationApk.sha256;
    finishedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');physicalDevice=$false;fullSuite=$false} |
    ConvertTo-Json -Depth 6 | Set-Content -LiteralPath "$ownedProof/device-$($Group.ToLowerInvariant())-$ownedStamp.json" -Encoding utf8NoBOM
if(!$ownedPassed){throw "Targeted $Group native checks failed; original evidence retained: $ownedLog"}
if($Group -in @('Chrome','All')){
    $ownedImages="$ownedProof/native-ui-$ownedStamp"
    New-Item -ItemType Directory -Path $ownedImages -Force | Out-Null
    foreach($ownedImage in @('prefix-preview.png','index-no-text.png','prefix-font2-320dp.png','index-font2-320dp.png','prefix-query-preview.png')){
        & $ownedAdb -s emulator-5570 pull "/sdcard/Android/data/com.yaprak.reader/files/yaprak-native-ui-0.173/$ownedImage" "$ownedImages/$ownedImage"
        if($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath "$ownedImages/$ownedImage" -PathType Leaf)){throw 'Owned UI screenshot pull failed'}
    }
}
if($Group -in @('Reader','All')){
    $ownedReaderImages="$ownedProof/native-reader-$ownedStamp"
    New-Item -ItemType Directory -Path $ownedReaderImages -Force | Out-Null
    foreach($ownedImage in @('horizontal-before-vertical.png','horizontal-after-vertical.png')){
        & $ownedAdb -s emulator-5570 pull "/sdcard/Android/data/com.yaprak.reader/files/yaprak-reader-0.175/$ownedImage" "$ownedReaderImages/$ownedImage"
        if($LASTEXITCODE -ne 0 -or !(Test-Path -LiteralPath "$ownedReaderImages/$ownedImage" -PathType Leaf)){throw 'Owned reader screenshot pull failed'}
    }
}
Write-Output "$ownedExpected actual Android $Group cases passed. Evidence: $ownedLog"
Write-Output 'Targeted owned emulator tests only; physical accessibility, other reader workflows, live providers and the full suite remain separate.'
