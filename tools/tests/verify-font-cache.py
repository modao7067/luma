#!/usr/bin/env python3
from pathlib import Path
import subprocess,tempfile
repo=Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='luma-font-cache-classes-') as directory:
    subprocess.run(['javac','--release','17','-d',directory,
                    str(repo/'shared/ui/java/io/github/modao7067/luma/shared/FontCacheInvalidator.java'),
                    str(repo/'tools/tests/font-cache/FontCacheInvalidatorCheck.java')],check=True)
    subprocess.run(['java','-cp',directory,'io.github.modao7067.luma.shared.FontCacheInvalidatorCheck'],check=True)
