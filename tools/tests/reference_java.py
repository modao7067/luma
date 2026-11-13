from pathlib import Path
import subprocess
BASELINE = '3ac24c8f1ed17ab5840bc7d85e2f9743fe81853f'
def materialize(repo):
    out = repo / 'tools/tests/build/reference-java-3ac24c8'
    if not (out / 'READY').exists():
        out.mkdir(parents=True, exist_ok=True)
        names = subprocess.check_output(
            ['git', 'ls-tree', '-r', '--name-only', BASELINE, 'shared'], cwd=repo, text=True).splitlines()
        for name in names:
            if not name.endswith('.java'):
                continue
            dest = out / name
            dest.parent.mkdir(parents=True, exist_ok=True)
            dest.write_bytes(subprocess.check_output(['git', 'show', BASELINE + ':' + name], cwd=repo))
        (out / 'READY').write_text(BASELINE + '\n')
    return out
