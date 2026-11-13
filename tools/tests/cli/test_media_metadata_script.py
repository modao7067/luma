import json
import os
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest

ROOT = Path(__file__).resolve().parents[3]
SOURCE = ROOT / 'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/runtime/RuntimeScripts.kt'

def program(name):
    source = SOURCE.read_text().split('val ' + name + ' = """', 1)[1].split('""".trimIndent()', 1)[0]
    return source.replace("${'$'}", '$')

def run(path):
    return subprocess.run([sys.executable, '-I', '-c', program('mediaMetadata'), str(path)],
                          capture_output=True, text=True, timeout=20)

class MediaMetadata(unittest.TestCase):
    def test_regular_file_reports_size_and_mime(self):
        with tempfile.TemporaryDirectory() as folder:
            target = Path(folder) / 'note.txt'; target.write_text('hello')
            result = run(target)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), {'size': 5, 'mime': 'text/plain'})

    def test_directory_reports_inode_directory(self):
        with tempfile.TemporaryDirectory() as folder:
            result = run(folder)
            self.assertEqual(result.returncode, 0, result.stderr)
            self.assertEqual(json.loads(result.stdout), {'size': 0, 'mime': 'inode/directory'})

    def test_special_file_is_rejected(self):
        with tempfile.TemporaryDirectory() as folder:
            fifo = Path(folder) / 'pipe'; os.mkfifo(fifo)
            result = run(fifo)
            self.assertNotEqual(result.returncode, 0)
            self.assertIn('只能读取普通文件', result.stderr)

if __name__ == '__main__':
    unittest.main()
