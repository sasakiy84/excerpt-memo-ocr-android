#!/usr/bin/env python3
"""Fetch research artifacts from pinned upstream revisions, verifying before use."""
import argparse, hashlib, json, os, tarfile, tempfile, urllib.request
from pathlib import Path

p=argparse.ArgumentParser();p.add_argument('directory',type=Path);p.add_argument('--only',nargs='+');a=p.parse_args()
a.directory.mkdir(parents=True,exist_ok=True)
for record in json.loads(Path(__file__).with_name('models-lock.json').read_text())['artifacts']:
    if a.only and record['local'] not in a.only:continue
    target=a.directory/record['local']
    if target.exists() and hashlib.file_digest(target.open('rb'),'sha256').hexdigest()==record['sha256']:continue
    with tempfile.NamedTemporaryFile(dir=a.directory,delete=False) as output:
        temporary=Path(output.name)
        try:
            with urllib.request.urlopen(record['url'],timeout=120) as response:
                while block:=response.read(1024*1024):output.write(block)
            output.close()
            if temporary.stat().st_size!=record['bytes'] or hashlib.file_digest(temporary.open('rb'),'sha256').hexdigest()!=record['sha256']:raise ValueError('Checksum mismatch: '+record['local'])
            os.replace(temporary,target)
        finally:temporary.unlink(missing_ok=True)
    print(target.name,record['bytes'])
archive=a.directory/'official-source.tar.gz';source=a.directory/'official-source'
if archive.exists() and not source.exists():
    locked=next(x for x in json.loads(Path(__file__).with_name('models-lock.json').read_text())['artifacts'] if x['local']==archive.name)
    if hashlib.file_digest(archive.open('rb'),'sha256').hexdigest()!=locked['sha256']:raise ValueError('Source checksum mismatch')
    with tarfile.open(archive) as stream:
        members=stream.getmembers();top=members[0].name.split('/')[0]
        with tempfile.TemporaryDirectory(dir=a.directory) as staging:
            stream.extractall(staging,filter='data');os.replace(Path(staging)/top,source)
