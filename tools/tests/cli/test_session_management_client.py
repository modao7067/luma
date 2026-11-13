#!/usr/bin/env python3
import json
import os
from pathlib import Path
import socket
import subprocess
import tempfile
import threading
import unittest

CLIENT = Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/sessions/luma-sessions'

class ClientTest(unittest.TestCase):
    def invoke(self, request, reply, stdin=False):
        with tempfile.TemporaryDirectory() as directory, socket.socket() as listener:
            listener.bind(('127.0.0.1', 0)); listener.listen(2); listener.settimeout(2)
            config = Path(directory) / 'binding.json'
            config.write_text(json.dumps({'host': '127.0.0.1', 'port': listener.getsockname()[1], 'token': 'fixture-capability'}))
            seen = []
            def serve():
                connection, _ = listener.accept()
                with connection:
                    seen.append(json.loads(connection.makefile('rb').readline()))
                    connection.sendall(reply)
            thread = threading.Thread(target=serve); thread.start()
            result = subprocess.run(['python3', str(CLIENT)] + ([] if stdin else [json.dumps(request)]),
                                    input=json.dumps(request) if stdin else None,
                                    env={**os.environ, 'LUMA_SESSIONS_CONFIG': str(config)},
                                    text=True, capture_output=True, timeout=5)
            thread.join(timeout=3)
            self.assertFalse(thread.is_alive()); self.assertEqual(1, len(seen))
            self.assertEqual('fixture-capability', seen[0].pop('token')); self.assertEqual(request, seen[0])
            self.assertNotIn('fixture-capability', result.stdout + result.stderr)
            listener.settimeout(.05)
            with self.assertRaises(socket.timeout): listener.accept()
            return result

    def test_authenticated_cli_request_and_stdin(self):
        response = {'ok': True, 'state': 'accepted', 'id': 'target'}
        result = self.invoke({'action': 'send', 'id': 'target', 'text': 'task', 'requestId': 'job-1'}, (json.dumps(response) + '\n').encode(), stdin=True)
        self.assertEqual(0, result.returncode); self.assertEqual(response, json.loads(result.stdout))

    def test_disconnect_preserves_request_identity_without_automatic_retry(self):
        result = self.invoke({'action': 'send', 'id': 'target', 'text': 'task', 'requestId': 'stable'}, b'')
        value = json.loads(result.stdout)
        self.assertEqual(1, result.returncode); self.assertEqual('unknown', value['state']); self.assertEqual('stable', value['requestId'])

    def test_partial_frame_is_unknown_not_accepted(self):
        result = self.invoke({'action': 'create', 'cli': 'pi', 'cwd': '/work', 'requestId': 'stable'}, b'{"ok":true}')
        self.assertEqual('unknown', json.loads(result.stdout)['state'])

    def test_help_needs_no_binding(self):
        result = subprocess.run(['python3', str(CLIENT), '--help'], env={}, text=True, capture_output=True)
        self.assertEqual(0, result.returncode); self.assertIn('requestId', result.stdout)

    def test_nonlocal_binding_is_not_contacted(self):
        with tempfile.TemporaryDirectory() as directory:
            config = Path(directory) / 'binding.json'
            config.write_text(json.dumps({'host': 'example.com', 'port': 80, 'token': 'fixture-capability'}))
            result = subprocess.run(['python3', str(CLIENT), '{"action":"list"}'], env={**os.environ, 'LUMA_SESSIONS_CONFIG': str(config)}, text=True, capture_output=True)
            self.assertEqual(1, result.returncode); self.assertNotIn('fixture-capability', result.stdout)

if __name__ == '__main__': unittest.main()
