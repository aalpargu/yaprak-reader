# Requires PowerShell 7 and one already authorized Android device/emulator.
# Reinstalls the two exact candidate APKs with -r; never clears data or uninstalls the app.
param(
    [string]$AdbPath,
    [string]$Serial,
    [string]$CandidateApk,
    [string]$TestApk,
    [switch]$PreflightOnly
)
$ErrorActionPreference='Stop'
$ownedRepo=Split-Path -Parent $PSScriptRoot
$ownedResult=Join-Path $ownedRepo 'docs/verification/candidate-results-0.164.0.json'
$ownedReport=Get-Content -LiteralPath $ownedResult -Raw | ConvertFrom-Json
if($ownedReport.version -ne '0.164.0' -or $ownedReport.status -ne 'CANDIDATE_NATIVE_VERIFICATION_PENDING'){
    throw 'This runner is only for the recorded 0.164.0 candidate.'
}
if(!$ownedReport.deviceTest.compiled -or !$ownedReport.instrumentationApk.delivery){
    throw 'The dedicated device test has not been compiled and recorded.'
}
if($CandidateApk){$ownedReport.apk.delivery=$CandidateApk}
if($TestApk){$ownedReport.instrumentationApk.delivery=$TestApk}
foreach($ownedArtifact in @($ownedReport.apk,$ownedReport.instrumentationApk)){
    if(!(Test-Path -LiteralPath $ownedArtifact.delivery -PathType Leaf) -or
        (Get-FileHash -LiteralPath $ownedArtifact.delivery -Algorithm SHA256).Hash -ne $ownedArtifact.sha256){
        throw 'Candidate APK missing or different from the recorded hash.'
    }
}
if(!$AdbPath){
    if($env:ANDROID_HOME){$AdbPath=Join-Path $env:ANDROID_HOME 'platform-tools/adb.exe'}
    else {$AdbPath=(Get-Command adb -ErrorAction Stop).Source}
}
$ownedDevices=@(& $AdbPath devices -l 2>&1)
if($LASTEXITCODE -ne 0){throw 'ADB device inspection failed.'}
$ownedReady=@(foreach($ownedLine in $ownedDevices){
    if([string]$ownedLine -match '^(\S+)\s+device(?:\s|$)'){$Matches[1]}
})
if($Serial){
    if($Serial -notin $ownedReady){throw 'The requested device is not connected and authorized.'}
} elseif($ownedReady.Count -eq 1){$Serial=$ownedReady[0]}
else {throw 'Connect exactly one authorized Android device, or specify -Serial. No APK was installed.'}
$ownedApiOutput=@(& $AdbPath -s $Serial shell getprop ro.build.version.sdk 2>&1)
if($LASTEXITCODE -ne 0){throw 'Cannot inspect Android API version.'}
$ownedApi=0
if(![int]::TryParse(($ownedApiOutput -join '').Trim(),[ref]$ownedApi) -or $ownedApi -lt 26){throw 'DOCX test requires Android API26 or later; no APK installed.'}
if($PreflightOnly){Write-Output 'Exact candidate APKs and authorized device found; no installation or test run requested.'; exit 0}

$ownedProof=Join-Path $ownedRepo 'docs/verification/docx-index-0.164.0'
$ownedLog=Join-Path $ownedProof ('device-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'.log')
function Invoke-OwnedAdb([string[]]$Arguments){
    $ownedOutput=@(& $AdbPath -s $Serial @Arguments 2>&1)
    $ownedExit=$LASTEXITCODE
    $ownedOutput | Add-Content -LiteralPath $ownedLog -Encoding utf8
    if($ownedExit -ne 0){throw "ADB action failed; see $ownedLog"}
    return $ownedOutput
}
"DOCX original-source FTS device tests; UTC $([DateTimeOffset]::UtcNow.ToString('o'))" | Set-Content -LiteralPath $ownedLog -Encoding utf8
foreach($ownedArtifact in @($ownedReport.apk,$ownedReport.instrumentationApk)){
    $ownedInstall=Invoke-OwnedAdb -Arguments @('install','-r',[string]$ownedArtifact.delivery)
    if(!($ownedInstall -match '^Success\s*$')){throw "APK installation did not confirm success; see $ownedLog"}
}
$ownedClass='com.yaprak.reader.formats.docx.DocxLibraryTextIndexDeviceTest'
$ownedInstrumentation=Invoke-OwnedAdb -Arguments @('shell','am','instrument','-w','-r','-e','class',$ownedClass,
    'com.yaprak.reader.test/androidx.test.runner.AndroidJUnitRunner')
$ownedText=$ownedInstrumentation -join "`n"
if($ownedText -notmatch '(?m)^OK \(2 tests\)\s*$' -or
    $ownedText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg='){
    throw "The device test did not pass exactly two tests; see $ownedLog"
}
Write-Output "Two actual Android FTS/source tests passed. Evidence: $ownedLog"
Write-Output 'This does not promote the candidate or establish the pending native Room/backup regressions, PDF acceptance or full suite.'
