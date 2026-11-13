import sys
from pathlib import Path
sys.path.insert(0, str(Path(__file__).absolute().parent))
from luma_cli.server import serve
if __name__ == "__main__":
    if len(sys.argv) != 4: raise SystemExit("Usage: main.py PORT PRIVATE_TOKEN SESSION_DIRECTORY")
    serve(*sys.argv[1:])
