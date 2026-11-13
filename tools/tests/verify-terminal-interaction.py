#!/usr/bin/env python3
from pathlib import Path
import subprocess
import tempfile

repo = Path(__file__).resolve().parents[2]
with tempfile.TemporaryDirectory(prefix='luma-terminal-interaction-') as classes:
    subprocess.run(['javac', '--release', '17', '-d', classes,
                    str(repo/'shared/terminal-view/java/com/termux/view/TerminalTextCopy.java'),
                    str(repo/'shared/ui/java/io/github/modao7067/luma/shared/TerminalFontSize.java'),
                    str(repo/'tools/tests/terminal-interaction/TerminalInteractionCheck.java')], check=True)
    subprocess.run(['java', '-cp', classes, 'io.github.modao7067.luma.shared.TerminalInteractionCheck'], check=True)
