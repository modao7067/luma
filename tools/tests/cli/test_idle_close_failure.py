#!/usr/bin/env python3
import pathlib
import subprocess
import sys
import tempfile
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
from test_idle_reclaim import NativeFixture, wait_for
from luma_cli.server import Manager
from luma_cli.transport import Conflict

class FailOnceStream:
    def __init__(self, stream): self.stream = stream; self.failed = False
    def __getattr__(self, name): return getattr(self.stream, name)
    def close(self):
        if not self.failed:
            self.failed = True
            raise OSError('fixture partial stream close failure')
        self.stream.close()

class CloseFailureTests(unittest.TestCase):
    def make(self, directory):
        manager = Manager(pathlib.Path(directory) / 'records')
        request = {'id': 'original', 'cli': 'fixture', 'cwd': directory}
        session = manager.connect(request)
        session.admit({'submissionId': 'once', 'text': 'retained native history'})
        wait_for(lambda: session.delivery_worker is None)
        self.assertEqual(session.config['receipts']['once'], 'accepted')
        request['nativeId'] = session.config['nativeId']
        return manager, request, session

    def assert_fenced_not_released(self, manager, session):
        self.assertIs(manager.get('original'), session)
        snapshot = session.snapshot()
        self.assertFalse(snapshot['released']); self.assertTrue(snapshot['releasePending'])
        self.assertFalse(snapshot['connected']); self.assertFalse(snapshot['connecting']); self.assertFalse(snapshot['canOpenTui'])
        self.assertEqual(snapshot['running'], session.data['running'])
        self.assertFalse(snapshot['releasable']); self.assertIn('重新连接完成清理', snapshot['error'])
        with self.assertRaises(Conflict): session.admit({'submissionId': 'half-close', 'text': 'must not send'})
        with self.assertRaises(Conflict): session.action('send', {'submissionId': 'half-close', 'text': 'must not send'})
        self.assertNotIn('half-close', session.config['deliveries'])

    def assert_original_history(self, session, native):
        self.assertEqual(session.config['nativeId'], native)
        self.assertEqual([r['text'] for r in session.rows.values()], ['retained native history'])
        self.assertEqual(session.config['receipts']['once'], 'accepted')

    def test_failed_terminate_stays_fenced_then_release_or_connect_retry_converges(self):
        for recovery in ('release', 'connect'):
            with self.subTest(recovery=recovery), tempfile.TemporaryDirectory(prefix='luma-close-terminate-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture}):
                manager, request, session = self.make(directory)
                peer = session.peer; terminate = peer.proc.terminate; calls = []
                def fail_once():
                    calls.append('terminate')
                    if len(calls) == 1: raise OSError('fixture terminate failure')
                    return terminate()
                original_start = NativeFixture.start
                starts = []
                def start_after_old_closed(replacement, *args, **kwargs):
                    self.assertIsNotNone(peer.proc.poll(), 'replacement started while old executor was alive')
                    starts.append(replacement)
                    return original_start(replacement, *args, **kwargs)
                try:
                    with patch.object(peer.proc, 'terminate', fail_once), patch.object(NativeFixture, 'start', start_after_old_closed):
                        with self.assertRaisesRegex(OSError, '释放未完成'):
                            manager.release('original', {'nativeId': request['nativeId']})
                        self.assertIsNone(peer.proc.poll())
                        self.assertEqual(starts, []); self.assert_fenced_not_released(manager, session)
                        self.assertTrue(session.data['connected'])
                        session.update(canOpenTui=True)
                        self.assertFalse(session.snapshot()['canOpenTui'])
                        if recovery == 'release':
                            result = manager.release('original', {'nativeId': request['nativeId']})
                            self.assertTrue(result['released']); self.assertFalse(manager.sessions)
                        restored = manager.connect(request)
                        self.assertEqual(len(starts), 1)
                        self.assertIsNot(restored, session); self.assertTrue(session.released)
                        self.assert_original_history(restored, request['nativeId'])
                        manager.release('original', {'nativeId': request['nativeId']})
                finally:
                    if peer.proc.poll() is None: peer.close()
                    for remaining in list(manager.sessions.values()):
                        if remaining.peer: remaining.peer.close()

    def test_second_wait_failure_can_be_retried_without_releasing_early(self):
        with tempfile.TemporaryDirectory(prefix='luma-close-wait-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture}):
            manager, request, session = self.make(directory)
            peer = session.peer; real_wait = peer.proc.wait; waits = []
            def uncertain_wait(timeout):
                waits.append(timeout)
                if len(waits) <= 2: raise subprocess.TimeoutExpired('fixture native process', timeout)
                return real_wait(timeout=timeout)
            try:
                with patch.object(peer.proc, 'terminate', lambda: None), patch.object(peer.proc, 'wait', uncertain_wait):
                    with self.assertRaisesRegex(OSError, '释放未完成'):
                        manager.release('original', {'nativeId': request['nativeId']})
                    self.assertEqual(len(waits), 2); self.assert_fenced_not_released(manager, session)
                    self.assertTrue(manager.release('original', {'nativeId': request['nativeId']})['released'])
                self.assertIsNotNone(peer.proc.poll()); self.assertFalse(manager.sessions)
                restored = manager.connect(request)
                self.assert_original_history(restored, request['nativeId'])
                manager.release('original', {'nativeId': request['nativeId']})
            finally:
                if peer.proc.poll() is None: peer.close()
                for remaining in list(manager.sessions.values()):
                    if remaining.peer: remaining.peer.close()

    def test_stream_close_failure_finishes_cleanup_before_original_id_reopen(self):
        with tempfile.TemporaryDirectory(prefix='luma-close-stream-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture}):
            manager, request, session = self.make(directory)
            peer = session.peer; stream = FailOnceStream(peer.proc.stdin)
            with patch.object(peer.proc, 'stdin', stream):
                with self.assertRaisesRegex(OSError, '释放未完成'):
                    manager.release('original', {'nativeId': request['nativeId']})
                self.assertIsNotNone(peer.proc.poll()); self.assertFalse(stream.closed)
                self.assert_fenced_not_released(manager, session)
                restored = manager.connect(request)
                self.assertTrue(stream.closed); self.assertTrue(peer.proc.stdout.closed); self.assertTrue(peer.proc.stderr.closed)
            self.assert_original_history(restored, request['nativeId'])
            manager.release('original', {'nativeId': request['nativeId']})

    def test_retired_dead_observer_close_failure_remains_retryable(self):
        with tempfile.TemporaryDirectory(prefix='luma-retire-stream-') as directory, patch('luma_cli.server.ADAPTERS', {'fixture': NativeFixture}):
            manager, request, session = self.make(directory)
            peer = session.peer
            session.update(running=True)
            peer.proc.terminate(); peer.proc.wait(timeout=3)
            wait_for(lambda: not session.data['connected'])
            stream = FailOnceStream(peer.proc.stdin)
            with patch.object(peer.proc, 'stdin', stream):
                with self.assertRaisesRegex(OSError, '释放未完成'): manager.connect(request)
                self.assert_fenced_not_released(manager, session)
                restored = manager.connect(request)
                self.assertIsNot(restored, session); self.assertTrue(stream.closed)
                self.assertTrue(session.released); self.assertFalse(session.close_pending)
            self.assert_original_history(restored, request['nativeId'])
            manager.release('original', {'nativeId': request['nativeId']})

if __name__ == '__main__': unittest.main(verbosity=2)
