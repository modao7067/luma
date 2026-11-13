#!/usr/bin/env python3
import pathlib
import sys
import tempfile
import unittest
from unittest import mock

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from test_media_projection import IMAGE, config
from luma_cli.base import _media_extension, ensure_root_media_cache_access
from luma_cli.pi import PiSession
from luma_cli.pi_native import PiNativeSession
from luma_cli.server import Manager
from luma_cli.claude import ClaudeSession
from luma_cli.kimi import KimiSession
from luma_cli.qoder import QoderSession
from luma_cli.dsh import DshSession
from luma_cli.antigravity import AntigravitySession
from luma_cli.antigravity_cli import AntigravityCliSession
from luma_cli.grok import GrokSession

BODIES = ('看一下这张图。', '', '[图片]\n[image]')

class UserImageProjection(unittest.TestCase):
    def assert_user(self, session, body):
        rows = [r for r in session.rows.values() if r['role'] == 'user']
        self.assertTrue(rows)
        self.assertEqual(''.join(r['text'] for r in rows), body)
        self.assertEqual(sum(len(r.get('media', [])) for r in rows), 1)

    def test_acp_user_chunks_do_not_add_attachment_captions(self):
        for cls in (DshSession, KimiSession, QoderSession, AntigravitySession, GrokSession):
            for body in BODIES:
                with self.subTest(adapter=cls.__name__, body=body):
                    s = cls(config(cls.__name__), lambda: None)
                    s.chunk_kind = s.chunk_id = s.current_submission = None
                    s.replaying_history = False; s.replayed_rows = set()
                    if cls is GrokSession:
                        s.loading = s.processing_replay = False; s.seen_events = set(); s.chunk_prompt = None
                        s.background_tools = {}; s.child_sessions = {}
                    for content in ([{'type': 'text', 'text': body}] if body else []) + [IMAGE]:
                        s.event({'method': 'session/update', 'params': {'update': {'sessionUpdate': 'user_message_chunk', 'content': content}}})
                    self.assert_user(s, body)

    def test_pi_rpc_sdk_and_claude_use_only_native_text_blocks(self):
        for cls in (PiSession, PiNativeSession, ClaudeSession):
            for body in BODIES:
                with self.subTest(adapter=cls.__name__, body=body):
                    s = cls(config(cls.__name__), lambda: None)
                    blocks = ([{'type': 'text', 'text': body}] if body else []) + [IMAGE]
                    if cls is ClaudeSession:
                        s.project({'type': 'user', 'uuid': 'native', 'message': {'role': 'user', 'content': blocks}})
                    else:
                        s.project({'id': 'native', 'role': 'user', 'content': blocks}, 'fallback')
                    self.assert_user(s, body)

    def test_grok_native_extension_has_no_generated_caption_even_in_replay(self):
        for replay in (False, True):
            for body in BODIES:
                s = GrokSession(config('Grok Build'), lambda: None)
                s.loading = s.processing_replay = False; s.seen_events = set(); s.chunk_prompt = None
                s.chunk_kind = s.chunk_id = s.current_submission = None; s.background_tools = {}; s.child_sessions = {}
                for content in ([{'type': 'text', 'text': body}] if body else []) + [IMAGE]:
                    s.event({'method': 'x.ai/session/update', 'params': {'update': {
                        'sessionUpdate': 'user_message_chunk', 'content': content}, '_meta': {'isReplay': replay}}})
                self.assert_user(s, body)

    def test_text_only_agy_keeps_literal_text_in_local_observed_history(self):
        for body in BODIES[::2]:
            s = AntigravityCliSession(config('Antigravity', nativeId='native'), lambda: None)
            s.peer = type('FixturePeer', (), {'send': lambda _, frame: None, 'closed': False})()
            self.assertFalse(s.prompt({'submissionId': 'client', 'text': body}))
            self.assertEqual(s.rows['client']['text'], body)
            self.assertEqual(s.config['localProjection']['messages'][0]['text'], body)
            self.assertNotIn('media', s.rows['client'])

    def test_pi_strips_trailing_sdk_image_hints_only(self):
        hint = '[Image: original 1600x900, displayed at 800x450. Multiply coordinates by 2.00 to map to original image.]'
        converted = '[Image converted from image/webp to image/png.]'
        omitted = '[Image omitted: too large.]'
        for cls in (PiSession, PiNativeSession):
            with self.subTest(adapter=cls.__name__):
                s = cls(config(cls.__name__), lambda: None)
                s.project({'id': 'u1', 'role': 'user', 'content': [{'type': 'text', 'text': f'帮我看看\n\n{converted}\n\n{hint}'}, IMAGE]}, 'f1')
                self.assertEqual(s.rows['u1']['text'], '帮我看看')
                s.project({'id': 'u2', 'role': 'user', 'content': [{'type': 'text', 'text': f'\n\n{hint}'}, IMAGE]}, 'f2')
                self.assertEqual(s.rows['u2']['text'], '')
                s.project({'id': 'u3', 'role': 'user', 'content': [{'type': 'text', 'text': f'保留 [Image: note]\n\n{omitted}\n\n后文'}, IMAGE]}, 'f3')
                self.assertEqual(s.rows['u3']['text'], f'保留 [Image: note]\n\n{omitted}\n\n后文')
                if cls is PiNativeSession:
                    s.project_queue({'followUp': [{'id': 'q1', 'message': f'队列文本\n\n{hint}'}]})
                else:
                    s.project_queue({'followUp': [f'队列文本\n\n{hint}']})
                self.assertEqual(s.data['queue'][0]['text'], '队列文本')

    def test_root_media_cache_permissions_and_webp_extension_fallback(self):
        self.assertEqual(_media_extension('image/webp'), '.webp')
        self.assertEqual(_media_extension('image/jpeg; charset=utf-8'), '.jpg')
        self.assertEqual(_media_extension('image/png'), '.png')
        with tempfile.TemporaryDirectory() as tmp:
            app_root = pathlib.Path(tmp) / 'runtime/agent/root'
            sessions_dir = app_root / 'cli-sessions'
            media_cache = sessions_dir / 'media-cache'
            media_cache.mkdir(parents=True)
            existing = media_cache / 'existing.webp'
            existing.write_bytes(b'RIFF\x00\x00\x00\x00WEBP')
            chown_calls = []
            chmod_calls = []
            fake_stat = mock.Mock(st_uid=10764, st_gid=10764)
            with mock.patch('luma_cli.base.os.geteuid', return_value=0), \
                 mock.patch('pathlib.Path.stat', return_value=fake_stat), \
                 mock.patch('luma_cli.base.os.chown', side_effect=lambda p, u, g: chown_calls.append((str(p), u, g))), \
                 mock.patch('luma_cli.base.os.chmod', side_effect=lambda p, m: chmod_calls.append((str(p), m))):
                Manager(str(sessions_dir))
                ensure_root_media_cache_access(sessions_dir, existing)
            self.assertIn((str(sessions_dir), 0o711), chmod_calls)
            self.assertIn((str(media_cache), 0o700), chmod_calls)
            self.assertIn((str(media_cache), 10764, 10764), chown_calls)
            self.assertIn((str(existing), 0o600), chmod_calls)
            self.assertIn((str(existing), 10764, 10764), chown_calls)

if __name__ == '__main__': unittest.main(verbosity=2)
