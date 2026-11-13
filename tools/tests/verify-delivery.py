#!/usr/bin/env python3
from pathlib import Path
import argparse
import copy
import importlib.util
import json
import unittest.mock as mock

REPO=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('delivery',REPO/'tools/delivery.py')
delivery=importlib.util.module_from_spec(spec);spec.loader.exec_module(delivery)
parser=argparse.ArgumentParser(description=__doc__)
parser.add_argument('--target',choices=['all','gui','luma'],default='all')
args=parser.parse_args()
if args.target=='luma':args.target='gui'
policies=json.loads((REPO/'tools/release-policy.json').read_text())
assets=json.loads((REPO/'tools/assets.json').read_text())
for name,policy in policies.items():
    if args.target not in ('all',name):continue
    commands={}
    for command in [(delivery.AAPT2,'dump','badging'),(delivery.AAPT2,'dump','permissions'),(delivery.APKSIGNER,'verify','--print-certs')]:
        commands[command]=delivery.output(*command,REPO/policy['apk'])
    def output(*args):return commands[tuple(args[:-1])]
    def rejects(config,manifest=assets):
        try:delivery.validate(name,config,manifest)
        except ValueError:return
        raise AssertionError('Invalid release accepted')
    with mock.patch.object(delivery,'output',side_effect=output):
        delivery.validate(name,policy,assets)
        changed=copy.deepcopy(policy);changed['package']='wrong.package';rejects(changed)
        changed=copy.deepcopy(policy);changed['signerSha256']=['0'*64];rejects(changed)
        changed=copy.deepcopy(policy);changed['allowedPermissions']=[];rejects(changed)
        changed_assets=copy.deepcopy(assets)
        first=next(k for k in changed_assets if k.startswith(policy['assetRoots'][0]))
        changed_assets[first]['sha256']='0'*64;rejects(policy,changed_assets)
        if policy.get('dependencyAssets'):
            changed=copy.deepcopy(policy)
            member=next(iter(changed['dependencyAssets']))
            del changed['dependencyAssets'][member]
            rejects(changed)
            latex=[key for key in policy['dependencyAssets'] if key.startswith('assets/org/scilab/forge/jlatexmath/')]
            if len(latex)>1:
                changed=copy.deepcopy(policy)
                changed['dependencyAssets'][latex[0]]['member']=policy['dependencyAssets'][latex[1]]['member']
                rejects(changed)
        if policy.get('sourceAssets'):
            member=next(iter(policy['sourceAssets']))
            changed=copy.deepcopy(policy);del changed['sourceAssets'][member]
            rejects(changed)
            changed=copy.deepcopy(policy);changed['sourceAssets'][member]='backend/root/not-reviewed.py'
            rejects(changed)
            altered=copy.deepcopy(assets)
            altered[policy['sourceAssets'][member]]['sha256']='0'*64
            rejects(policy,altered)
        real_zip=delivery.zipfile.ZipFile
        class PrivateEntryView:
            def __init__(self,*args,**kwargs):self.archive=real_zip(*args,**kwargs)
            def __enter__(self):return self
            def __exit__(self,*args):self.archive.close()
            def namelist(self):return self.archive.namelist()+['assets/signing/private.jks']
            def read(self,*args):return self.archive.read(*args)
        with mock.patch.object(delivery.zipfile,'ZipFile',PrivateEntryView):rejects(policy)
    print('PASS',name,'package, signer, permissions, reviewed assets, private-file rejection')
print('ALL PASS release invariants; no APK created')
