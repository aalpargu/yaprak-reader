$Group='Reader'
$ErrorActionPreference='Stop'
$ownedRepo='C:/Users/alpar/Documents/Codex/2026-09-19/i-want-to-build-a-personal/outputs/Yaprak'
$ownedAdb='C:/Users/alpar/Documents/Codex/2026-09-01/files-pasted-by-the-user-android/work/toolchain/sdk/platform-tools/adb.exe'
$ownedReport=Get-Content -LiteralPath "$ownedRepo/docs/verification/candidate-results-0.173.0.json" -Raw | ConvertFrom-Json
if($ownedReport.version -ne '0.173.0'){throw 'Unexpected candidate'}
foreach($ownedArtifact in @($ownedReport.apk,$ownedReport.instrumentationApk)){
    if((Get-FileHash -LiteralPath $ownedArtifact.delivery -Algorithm SHA256).Hash -ne $ownedArtifact.sha256){throw 'Host artifact mismatch'}
}
$ownedName=@(& $ownedAdb -s emulator-5570 emu avd name 2>&1)
if($LASTEXITCODE -ne 0 -or $ownedName -notcontains 'yaprak-source-api35'){throw 'Only the owned emulator is allowed'}
$ownedBoot=(& $ownedAdb -s emulator-5570 shell getprop sys.boot_completed 2>&1 | Out-String).Trim()
$ownedApi=(& $ownedAdb -s emulator-5570 shell getprop ro.build.version.sdk 2>&1 | Out-String).Trim()
if($ownedBoot -ne '1' -or $ownedApi -ne '35'){throw 'The owned API35 emulator is not ready'}
$ownedPackage=(& $ownedAdb -s emulator-5570 shell dumpsys package com.yaprak.reader 2>&1 | Out-String)
if($LASTEXITCODE -ne 0 -or $ownedPackage -notmatch 'versionCode=174\b' -or $ownedPackage -notmatch 'versionName=0\.173\.0\b'){throw 'The exact 0.173 candidate must already be installed by its hash-gated runner'}
$ownedCases=@(
    @{name='com.yaprak.reader.reader.EpubReaderDeviceTest';count=3},
    @{name='com.yaprak.reader.reader.PdfReaderDeviceTest';count=2},
    @{name='com.yaprak.reader.reader.PdfZoomResumeDeviceTest';count=1},
    @{name='com.yaprak.reader.reader.PdfHorizontalDeviceTest';count=1},
    @{name='com.yaprak.reader.reader.PdfPageLinksDeviceTest';count=1}
)
$ownedExpected=($ownedCases | Measure-Object -Property count -Sum).Sum
$ownedNames=($ownedCases | ForEach-Object {$_.name}) -join ','
$ownedProof="$ownedRepo/docs/verification/prefix-keyword-sources-0.173.0"
$ownedStamp=[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')
$ownedLog="$ownedProof/device-core-$($Group.ToLowerInvariant())-$ownedStamp.log"
@(
    "Owned actual Android35 targeted $Group checks; UTC $([DateTimeOffset]::UtcNow.ToString('o'))",
    "Main SHA256 $($ownedReport.apk.sha256)",
    "Test SHA256 $($ownedReport.instrumentationApk.sha256)",
    "Classes: $ownedNames"
) | Set-Content -LiteralPath $ownedLog -Encoding utf8NoBOM
$ownedOutput=@(& $ownedAdb -s emulator-5570 shell am instrument -w -r -e class $ownedNames 'com.yaprak.reader.test/androidx.test.runner.AndroidJUnitRunner' 2>&1)
$ownedExit=$LASTEXITCODE
$ownedOutput | Add-Content -LiteralPath $ownedLog -Encoding utf8NoBOM
$ownedText=$ownedOutput -join "`n"
$ownedPassed=$ownedExit -eq 0 -and $ownedText -match "(?m)^OK \($ownedExpected tests?\)\s*$" -and
    $ownedText -notmatch 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg=|INSTRUMENTATION_STATUS_CODE: -[1-4]'
[ordered]@{
    version='0.173.0';group=$Group;serial='emulator-5570';avd='yaprak-source-api35';api=35;
    expected=$ownedExpected;classes=$ownedCases;passed=$ownedPassed;adbExit=$ownedExit;
    log=(Split-Path -Leaf $ownedLog);mainSha256=$ownedReport.apk.sha256;testSha256=$ownedReport.instrumentationApk.sha256;
    finishedAtUtc=[DateTimeOffset]::UtcNow.ToString('o');physicalDevice=$false;fullSuite=$false
} | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath "$ownedProof/device-core-$($Group.ToLowerInvariant())-$ownedStamp.json" -Encoding utf8NoBOM
if(!$ownedPassed){throw "Targeted $Group native checks failed; original evidence retained: $ownedLog"}
Write-Output "$ownedExpected actual Android $Group cases passed. Evidence: $ownedLog"
