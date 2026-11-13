#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='luma-terminal-scroll-') as work:
    subprocess.run(['javac', '--release', '17', '-d', work,
                    str(repo/'shared/terminal-view/java/com/termux/view/TerminalScrollSpeed.java'),
                    str(repo/'tools/tests/terminal-scroll/ScrollChecks.java')], check=True)
    subprocess.run(['java', '-cp', work, 'com.termux.view.ScrollChecks'], check=True)
