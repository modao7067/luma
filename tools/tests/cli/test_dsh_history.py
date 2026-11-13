#!/usr/bin/env python3
import copy
import pathlib
import sys
import unittest
from unittest.mock import patch

sys.dont_write_bytecode = True
ROOT = pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0, str(ROOT / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.acp import AcpSession
from luma_cli.dsh import DshSession
from luma_cli.transport import Rejected

def page(messages=(), through=12, following=None, native='native'):
    return {'sessionId': native, 'throughSeq': through, 'title': 'native title',
            'messages': list(messages), 'nextOffset': following}

def user(native, text='identical'):
    return {'id': 'dsh-message-' + native, 'nativeMessageId': native,
            'role': 'user', 'text': text}

class HistoryProtocol(unittest.TestCase):
    def make(self, receipts=None, mapping=None):
        s = DshSession({'cli': 'DeepSeek Harness', 'cwd': '/tmp', 'nativeId': 'native',
                        'receipts': receipts or {}}, lambda: None)
        if mapping is not None:
            s.config['submissionNativeIds'] = mapping
        return s

    def test_pages_and_actual_ack_map_only_original_submission(self):
        s = self.make({'one': 'accepted'}, {'one': 'native-msg'})
        calls = []

        def call(method, params):
            calls.append((method, params))
            if params['offset'] == 0:
                return page([user('native-msg')], following=1)
            return page([{'id': 'assistant', 'role': 'assistant', 'text': 'native answer'}])

        s.call = call
        s.restore_history()
        self.assertTrue(s.data['historyReady'])
        self.assertEqual(list(s.rows), ['one', 'assistant'])
        self.assertEqual(s.data['title'], 'native title')
        self.assertEqual(calls[1], ('luma/history/read',
            {'sessionId': 'native', 'offset': 1, 'throughSeq': 12}))

    def test_legacy_unknown_is_not_accepted_or_text_matched(self):
        s = self.make({'unknown-original': 'unknown', 'accepted-original': 'accepted'})
        before = copy.deepcopy(s.config['receipts'])
        s.call = lambda *a: page([user('a'), user('b')])
        s.restore_history()
        self.assertEqual(s.config['receipts'], before)
        self.assertEqual(list(s.rows), ['dsh-message-a', 'dsh-message-b'])
        self.assertNotIn('submissionNativeIds', s.config)

    def test_mapped_unknown_remains_unknown(self):
        s = self.make({'pending-original': 'unknown'}, {'pending-original': 'native-msg'})
        s.call = lambda *a: page([user('native-msg')])
        s.restore_history()
        self.assertEqual(list(s.rows), ['pending-original'])
        self.assertEqual(s.config['receipts']['pending-original'], 'unknown')

    def test_foreign_identity_rejects_before_mutating_rows(self):
        s = self.make()
        s.row({'id': 'original', 'role': 'assistant', 'text': 'keep'})
        s.call = lambda *a: page(native='foreign')
        self.assertRaises(Rejected, s.restore_history)
        self.assertEqual(list(s.rows), ['original'])
        self.assertFalse(s.data.get('historyReady', False))

    def test_page_boundary_change_is_not_partially_applied(self):
        s = self.make()
        s.row({'id': 'original', 'role': 'assistant', 'text': 'keep'})
        replies = iter([page([user('first')], through=1, following=1),
                        page([user('second')], through=2)])
        s.call = lambda *a: next(replies)
        self.assertRaises(Rejected, s.restore_history)
        self.assertEqual(list(s.rows), ['original'])

    def test_busy_read_cannot_overwrite_live_rows(self):
        s = self.make()
        s.data['running'] = True
        s.row({'id': 'live', 'role': 'assistant', 'text': 'partial'})
        s.call = lambda *a: page([user('native-history')])
        self.assertRaises(Rejected, s.restore_history)
        self.assertEqual(list(s.rows), ['live'])

    def test_duplicate_native_rows_reject_before_application(self):
        s = self.make()
        s.call = lambda *a: page([user('duplicate'), user('duplicate')])
        self.assertRaises(Rejected, s.restore_history)
        self.assertFalse(s.rows)

    def test_missing_or_invalid_boundary_never_marks_history_ready(self):
        for boundary in (None, True, -2, '7'):
            with self.subTest(boundary=boundary):
                s = self.make()
                s.call = lambda *a: page(through=boundary)
                self.assertRaises(Rejected, s.restore_history)
                self.assertFalse(s.data.get('historyReady', False))

    def test_connect_marks_new_empty_native_session_ready_without_history_inference(self):
        s = self.make()
        s.config.pop('nativeId')
        s.permission_unavailable = 'fixture permission service absent'

        def connected(session):
            session.initialize_info = {}
            session.update(nativeId='new-native', connected=True)

        s.call = lambda *a: self.fail('New empty sessions do not read history or set permissions')
        with patch.object(AcpSession, 'connect', connected):
            s.connect()
        self.assertTrue(s.data['historyReady'])
        self.assertEqual(s.data['nativeId'], 'new-native')

    def test_old_carrier_or_failed_history_cannot_authorize_empty_cleanup(self):
        for available in (False, True):
            with self.subTest(historyAvailable=available):
                s = self.make()
                s.permission_unavailable = 'fixture permission service absent'

                def connected(session):
                    session.initialize_info = {'_meta': {
                        'lumaHistory': {'protocol': 1, 'available': available}}}
                    session.update(connected=True)

                def failed(*a):
                    raise Rejected('Native history unavailable')

                s.call = failed
                with patch.object(AcpSession, 'connect', connected):
                    s.connect()
                self.assertFalse(s.data['historyReady'])
                self.assertTrue(s.data['error'])
                self.assertEqual(s.data['nativeId'], 'native')

if __name__ == '__main__':
    unittest.main(verbosity=2)
