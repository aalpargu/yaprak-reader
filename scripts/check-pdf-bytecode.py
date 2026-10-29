"""Inspect actual DEX instructions; optionally identify retained release owners from exact R8 mapping.

Use the Android SDK dexdump executable, not a constant-pool text search. A release check
does not independently attribute every method that R8 inlined into another class.
"""
from pathlib import Path
import argparse
import hashlib
import json
import re
import subprocess
import tempfile
import zipfile


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--apk', required=True, type=Path)
    parser.add_argument('--dexdump', required=True, type=Path)
    parser.add_argument('--report', required=True, type=Path)
    parser.add_argument('--mapping', type=Path)
    parser.add_argument('--require-room', action='store_true')
    args = parser.parse_args()
    report = args.report.resolve()
    if report.exists():
        parser.error('Preserve previous evidence: choose a new report path')
    if args.mapping and args.require_room:
        parser.error('Room implementation names are checked on the unobfuscated debug APK')
    pdf_owners = set()
    if args.mapping:
        for line in args.mapping.read_text(encoding='utf-8').splitlines():
            match = re.fullmatch(r'com\.tom_roush\.[^ ]+ -> ([^ ]+):', line)
            if match:
                pdf_owners.add('L'+match.group(1).replace('.', '/')+';')
        if len(pdf_owners) <= 30:
            parser.error('Mapping must contain actual retained PDF class owners')
    seen, room, calls = set(), set(), []
    # Only newly created, context-managed scratch DEX files; originals remain read-only.
    with tempfile.TemporaryDirectory(prefix='yaprak-pdf-dex-') as raw:
        scratch = Path(raw)
        with zipfile.ZipFile(args.apk) as archive:
            dex_names = [name for name in archive.namelist() if re.fullmatch(r'classes\d*\.dex', name)]
            if not dex_names:
                parser.error('APK has no DEX entries')
            for name in dex_names:
                dex = scratch/name
                dex.write_bytes(archive.read(name))
                process = subprocess.Popen([str(args.dexdump.resolve()), '-d', str(dex)], stdout=subprocess.PIPE,
                                           stderr=subprocess.PIPE, text=True, encoding='utf-8', errors='replace')
                current, is_pdf = '', False
                for line in process.stdout:
                    match = re.search(r"Class descriptor\s*:\s*'([^']+)'", line)
                    if match:
                        current = match.group(1)
                        is_pdf = current in pdf_owners if args.mapping else (
                            current.startswith('Lcom/tom_roush/') and not current.endswith('/R;') and '/R$' not in current)
                        if is_pdf:
                            seen.add(current)
                        if current.endswith(('/LibraryDatabase_Impl;', '/TextIndexDatabase_Impl;')):
                            room.add(current)
                    if is_pdf and 'invoke-' in line and 'Landroid/util/Log;' in line:
                        calls.append(dict(dex=name, owner=current))
                stderr = process.stderr.read()
                if process.wait() != 0:
                    raise RuntimeError('dexdump failed: '+stderr[:200])
    sha = lambda path: hashlib.sha256(path.read_bytes()).hexdigest().upper()
    result = dict(apkSha256=sha(args.apk), dexFiles=len(dex_names), generatedRoomImplementations=sorted(room),
                  calls=calls, fullFeatureAcceptance=False)
    if args.mapping:
        result.update(mappingSha256=sha(args.mapping), mappedPdfOwners=len(pdf_owners), actualMappedPdfOwners=len(seen),
                      androidLogInvocationsFromMappedPdfOwners=len(calls), releaseExecutedOnDevice=False,
                      scope='Retained class owners from this R8 mapping; not independent attribution of every inlined method origin')
    else:
        result.update(actualPdfClasses=len(seen), androidLogInvocationsFromPdfClasses=len(calls))
    report.parent.mkdir(parents=True, exist_ok=True)
    report.write_text(json.dumps(result, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(result))
    minimum = 30 if args.mapping else 500
    if len(seen) <= minimum or calls or (args.require_room and len(room) != 2):
        raise SystemExit('Actual bytecode check failed; the report preserves the observed result')


if __name__ == '__main__':
    main()
