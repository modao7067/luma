#!/usr/bin/env python3
import sys as _sys, pathlib as _pl; _sys.path.insert(0, str(next(_p for _p in _pl.Path(__file__).resolve().parents if (_p/'toolenv.py').is_file()))); import toolenv
import argparse
import hashlib
import json
from pathlib import Path, PurePosixPath
import re
import subprocess
import zipfile

REPO = Path(__file__).resolve().parents[1]
BASE = REPO / 'tools'
AAPT2 = toolenv.AAPT2
APKSIGNER = toolenv.APKSIGNER
PRIVATE_PARTS = {'signing', '.git', '.gradle', '__pycache__'}
PRIVATE_NAMES = {'local.properties', '.env', '.bash_history', 'root-backend.token'}
PRIVATE_SUFFIXES = {'.jks', '.keystore', '.key', '.pem', '.db'}

def require(condition, message):
    if not condition:
        raise ValueError(message)

def output(*args):
    return subprocess.check_output(list(map(str, args)), text=True)

def package_info(apk):
    badging = output(AAPT2, 'dump', 'badging', apk)
    package = re.search(r"^package: name='([^']+)' versionCode='([^']+)' versionName='([^']*)'", badging)
    require(package is not None, 'Cannot read APK package/version')
    return dict(zip(('package', 'versionCode', 'version'), package.groups()))

def packaged_assets(apk):
    with zipfile.ZipFile(apk) as archive:
        return {name: hashlib.sha256(archive.read(name)).hexdigest()
                for name in archive.namelist()
                if name.startswith(('assets/', 'lib/')) and not name.endswith('/')}

def validate(name, policy, assets):
    apk = REPO / policy['apk']
    require(apk.is_file(), f'{name}: build APK first: {apk}')
    info = package_info(apk)
    require(info['package'] == policy['package'], f'{name}: package changed')
    gradle = (REPO / policy['project'] / 'app/build.gradle').read_text()
    require(info['versionCode'] == re.search(r'versionCode\s+(\d+)', gradle)[1], f'{name}: versionCode differs from source')
    require(info['version'] == re.search(r"versionName\s+'([^']+)'", gradle)[1], f'{name}: versionName differs from source')
    certificates = output(APKSIGNER, 'verify', '--print-certs', apk)
    signers = re.findall(r'certificate SHA-256 digest: (\w+)', certificates)
    require(sorted(signers) == sorted(policy['signerSha256']), f'{name}: signing certificate changed')
    permissions = set(re.findall(r"uses-permission[^:]*: name='([^']+)'", output(AAPT2, 'dump', 'permissions', apk)))
    require(permissions <= set(policy['allowedPermissions']), f'{name}: permissions exceed release policy')
    with zipfile.ZipFile(apk) as archive:
        for member in archive.namelist():
            path = PurePosixPath(member)
            require(not (set(path.parts) & PRIVATE_PARTS or path.name in PRIVATE_NAMES
                         or path.suffix.lower() in PRIVATE_SUFFIXES or path.name.endswith('release.properties')),
                    f'{name}: private file packaged: {member}')
        for source in policy.get('requiredResourceFiles', []):
            content = (REPO/source).read_bytes()
            require(any(info.filename.startswith('res/') and info.file_size == len(content)
                        and archive.read(info) == content for info in archive.infolist()),
                    f'{name}: required resource absent or changed: {source}')
    current_assets = packaged_assets(apk)
    expected = {}
    for source, row in assets.items():
        prefix = next((root for root in policy['assetRoots'] if source.startswith(root)), None)
        if prefix is None:
            continue
        member = source[len(prefix):]
        if member.startswith('jniLibs/'):
            member = 'lib/' + member[len('jniLibs/'):]
        if member.startswith(('assets/', 'lib/')):
            expected[member] = row['sha256']
    for member, source in policy.get('sourceAssets', {}).items():
        require(source in assets, f'{name}: unreviewed source asset: {source}')
        require(member.startswith(('assets/', 'lib/')) and member not in expected,
                f'{name}: invalid or duplicate source asset mapping: {member}')
        expected[member] = assets[source]['sha256']
    with zipfile.ZipFile(apk) as archive:
        for member, source in policy.get('generatedAssets', {}).items():
            require(archive.read(member) == (REPO/source).read_bytes(), f'{name}: generated asset differs: {member}')
            current_assets.pop(member)
        for member, source in policy.get('dependencyAssets', {}).items():
            with zipfile.ZipFile(REPO/source['archive']) as dependency:
                require(archive.read(member) == dependency.read(source['member']), f'{name}: dependency asset differs: {member}')
            current_assets.pop(member)
    require(current_assets == expected, f'{name}: packaged assets/native libraries differ from reviewed assets.json')
    rel_apk = apk.resolve().relative_to(REPO.resolve()).as_posix() if apk.resolve().is_relative_to(REPO.resolve()) else apk.name
    return {'app': name, 'apk': rel_apk, **info,
            'sha256': hashlib.file_digest(apk.open('rb'), 'sha256').hexdigest(),
            'signerSha256': signers, 'permissions': sorted(permissions),
            'reviewedAssetNativeEntries': len(current_assets), 'invariantsPassed': True}

def compare(old, new):
    before, after = packaged_assets(old), packaged_assets(new)
    with zipfile.ZipFile(old) as a, zipfile.ZipFile(new) as b:
        same_manifest = a.read('AndroidManifest.xml') == b.read('AndroidManifest.xml')
    rel_old = old.resolve().relative_to(REPO.resolve()).as_posix() if old.resolve().is_relative_to(REPO.resolve()) else old.name
    return {'oldApk': rel_old, 'oldVersion': package_info(old), 'manifestUnchanged': same_manifest,
            'addedAssets': sorted(after.keys() - before.keys()),
            'removedAssets': sorted(before.keys() - after.keys()),
            'changedAssets': sorted(k for k in before.keys() & after.keys() if before[k] != after[k])}

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--target', choices=['all', 'gui', 'luma'], default='all')
    parser.add_argument('--compare-old', action='store_true', help='Report old APK differences without rejecting legitimate version/asset changes')
    parser.add_argument('--gui-old', '--luma-old', dest='gui_old', type=Path)
    args = parser.parse_args()
    if args.target == 'luma': args.target = 'gui'
    policy = json.loads((BASE / 'release-policy.json').read_text())
    assets = json.loads((BASE / 'assets.json').read_text())
    rows = []
    for name, config in policy.items():
        if args.target not in ('all', name):
            continue
        row = validate(name, config, assets)
        old = getattr(args, name.replace('-', '_') + '_old')
        if args.compare_old or old is not None:
            reference = old or (REPO / config['referenceApk'] if config.get('referenceApk') else None)
            row['oldComparison'] = compare(reference, REPO / config['apk']) if reference else {'status': 'no-reference', 'reason': 'First release; no previous APK configured'}
        rows.append(row)
    print(json.dumps(rows, indent=2))
    (BASE / 'delivery.json').write_text(json.dumps(rows, indent=2) + '\n')

if __name__ == '__main__':
    main()
