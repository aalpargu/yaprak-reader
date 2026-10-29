$ErrorActionPreference='Stop'
$ownedRepo=Split-Path -Parent $PSScriptRoot
$ownedAdb='C:/Users/alpar/Documents/Codex/2026-09-01/files-pasted-by-the-user-android/work/toolchain/sdk/platform-tools/adb.exe'
$ownedSerial='emulator-5570'
$ownedReport=Get-Content -LiteralPath (Join-Path $ownedRepo 'docs/verification/candidate-results-0.170.0.json') -Raw | ConvertFrom-Json
if($ownedReport.version -ne '0.170.0' -or $ownedReport.versionCode -ne 171){throw 'Wrong candidate descriptor'}
foreach($ownedArtifact in @($ownedReport.apk,$ownedReport.instrumentationApk)){
    if((Get-FileHash -LiteralPath $ownedArtifact.delivery -Algorithm SHA256).Hash -ne $ownedArtifact.sha256){throw 'Candidate artifact changed'}
}
$ownedAvd=@(& $ownedAdb -s $ownedSerial emu avd name 2>&1)
if($LASTEXITCODE -ne 0 -or !($ownedAvd | Where-Object { ([string]$_).Trim() -eq 'yaprak-source-api35' })){throw 'Only the private task emulator is allowed'}
$ownedBoot=@(& $ownedAdb -s $ownedSerial shell getprop sys.boot_completed 2>&1)
if($LASTEXITCODE -ne 0 -or ($ownedBoot -join '').Trim() -ne '1'){throw 'Private Android boot incomplete'}
$ownedProof=Join-Path $ownedRepo 'docs/verification/epub-prefix-assistant-0.170.0'
$ownedLog=Join-Path $ownedProof ('device-docx-extra-'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')+'.log')
function Invoke-OwnedAdb([string[]]$Arguments){
    $ownedOutput=@(& $ownedAdb -s $ownedSerial @Arguments 2>&1)
    $ownedExit=$LASTEXITCODE
    $ownedOutput | Add-Content -LiteralPath $ownedLog -Encoding utf8
    if($ownedExit -ne 0){throw "ADB action failed; see $ownedLog"}
    return $ownedOutput
}
"Exact0.170 inherited9 DOCX/source/single-book tests on private Android35 emulator; UTC $([DateTimeOffset]::UtcNow.ToString('o')); no physical user data or live provider" | Set-Content -LiteralPath $ownedLog -Encoding utf8
foreach($ownedArtifact in @($ownedReport.apk,$ownedReport.instrumentationApk)){
    $ownedInstall=Invoke-OwnedAdb -Arguments @('install','-r',[string]$ownedArtifact.delivery)
    if(!($ownedInstall -match '^Success\s*$')){throw "APK installation not confirmed: $ownedLog"}
}
$ownedInstalled=Invoke-OwnedAdb -Arguments @('shell','dumpsys','package','com.yaprak.reader')
if(!($ownedInstalled -match 'versionCode=171(?:\s|$)') -or !($ownedInstalled -match 'versionName=0\.170\.0(?:\s|$)')){throw 'Installed target version mismatch'}
$ownedClass='com.yaprak.reader.formats.docx.DocxLibraryTextIndexDeviceTest'
$ownedOutput=Invoke-OwnedAdb -Arguments @('shell','am','instrument','-w','-r','-e','class',$ownedClass,'com.yaprak.reader.test/androidx.test.runner.AndroidJUnitRunner')
$ownedText=$ownedOutput -join "`n"
if($ownedText -notmatch '(?m)^OK \(9 tests\)\s*$' -or $ownedText -match 'FAILURES!!!|INSTRUMENTATION_FAILED|Process crashed|shortMsg='){
    throw "Exactly nine current-APK DOCX tests did not pass; see $ownedLog"
}
"Nine actual Android DOCX/source/single-book tests passed for exact0.170 APKs. Evidence: $ownedLog"
'This is source/SQL isolation only, not native Compose/reader/backup/physical/live-provider/full-suite acceptance.'
