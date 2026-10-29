"""Check real Junrar DEX owners for parser logging calls, optionally through exact R8 mapping."""
from pathlib import Path
import argparse,hashlib,json,re,subprocess,tempfile,zipfile

def main():
    parser=argparse.ArgumentParser(description=__doc__)
    for name in ['apk','dexdump','report']:parser.add_argument('--'+name,required=True,type=Path)
    parser.add_argument('--mapping',type=Path)
    args=parser.parse_args()
    if args.report.exists():parser.error('Preserve previous evidence')
    mapped=set()
    logger_owners={'Lorg/slf4j/Logger;','Landroid/util/Log;'}
    if args.mapping:
        for line in args.mapping.read_text(encoding='utf-8').splitlines():
            match=re.fullmatch(r'com\.github\.junrar\.[^ ]+ -> ([^ ]+):',line)
            if match:mapped.add('L'+match[1].replace('.','/')+';')
            logger=re.fullmatch(r'org\.slf4j\.Logger -> ([^ ]+):',line)
            if logger:logger_owners.add('L'+logger[1].replace('.','/')+';')
        if len(mapped)<10:parser.error('Mapping does not contain the actual decoder')
    seen=set();calls=[];room=set()
    with tempfile.TemporaryDirectory(prefix='yaprak-cbr-dex-') as scratch:
        with zipfile.ZipFile(args.apk) as archive:
            dex_names=[n for n in archive.namelist() if re.fullmatch(r'classes\d*\.dex',n)]
            if not dex_names:parser.error('No actual DEX entries')
            for name in dex_names:
                dex=Path(scratch)/name;dex.write_bytes(archive.read(name))
                process=subprocess.Popen([str(args.dexdump.resolve()),'-d',str(dex)],stdout=subprocess.PIPE,
                                         stderr=subprocess.PIPE,text=True,encoding='utf-8',errors='replace')
                owner='';selected=False
                for line in process.stdout:
                    match=re.search(r"Class descriptor\s*:\s*'([^']+)'",line)
                    if match:
                        owner=match[1];selected=owner in mapped if args.mapping else owner.startswith('Lcom/github/junrar/')
                        if selected:seen.add(owner)
                        if owner.endswith(('/LibraryDatabase_Impl;','/TextIndexDatabase_Impl;')):room.add(owner)
                    # The method target follows the register list. A Logger return descriptor
                    # from LoggerFactory.getLogger is not a call to Logger.
                    target=re.search(r'\},\s+(L[^ ;]+;)\.',line) if 'invoke-' in line else None
                    if selected and target and target[1] in logger_owners:
                        calls.append({'dex':name,'owner':owner})
                stderr=process.stderr.read()
                if process.wait()!=0:raise RuntimeError('dexdump failed: '+stderr[:200])
    sha=lambda f:hashlib.sha256(f.read_bytes()).hexdigest().upper()
    result={'apkSha256':sha(args.apk),'dexFiles':len(dex_names),'actualJunrarOwners':len(seen),
            'parserLogInvocations':calls,'generatedRoomImplementations':sorted(room),
            'fullFeatureAcceptance':False}
    if args.mapping:result.update(mappingSha256=sha(args.mapping),mappedJunrarOwners=len(mapped),
        scope='Retained class owners from this mapping; not independent attribution of every inlined origin',releaseExecutedOnDevice=False)
    args.report.parent.mkdir(parents=True,exist_ok=True)
    args.report.write_text(json.dumps(result,indent=2)+'\n',encoding='utf-8');print(json.dumps(result))
    if calls or len(seen)<(10 if args.mapping else 75) or not args.mapping and len(room)!=2:
        raise SystemExit('Actual bytecode check failed; observed evidence preserved')

if __name__=='__main__':main()
