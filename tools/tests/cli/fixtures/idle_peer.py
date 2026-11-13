import json
import os
from pathlib import Path
import sys

path, native = Path(sys.argv[1]), sys.argv[2]
rows = json.loads(path.read_text()) if path.exists() else []
for line in sys.stdin:
    frame = json.loads(line)
    if frame['method'] == 'append':
        params = frame['params']
        result = {'id': params['id'], 'role': 'user', 'text': params['text'], 'origin': 'native'}
        rows.append(result)
        with path.open('w') as out:
            json.dump(rows, out); out.flush(); os.fsync(out.fileno())
    elif frame['method'] == 'read':
        result = {'nativeId': native, 'messages': rows}
    else:
        raise AssertionError('unexpected native call')
    print(json.dumps({'jsonrpc': '2.0', 'id': frame['id'], 'result': result}), flush=True)
