#!/usr/bin/env python3
import json
from pathlib import Path
import re
import signal
import tempfile
import unittest
from unittest.mock import patch

ROOT = Path(__file__).resolve().parents[2]
source = (ROOT/'apps/gui/app/src/main/java/io/github/modao7067/luma/gui/runtime/RuntimeScripts.kt').read_text()
program = re.search(r'val cliBridgeStop = """\n(.*?)\n"""\.trimIndent\(\)', source, re.S).group(1)
ns = {'__name__': 'offline_fixture'}
exec(compile(program, 'production-cli-bridge-stop.py', 'exec'), ns)

class RestartContract(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory(prefix='luma-offline-restart-')
        self.addCleanup(self.directory.cleanup)
        self.base = Path(self.directory.name)
        self.record = self.base/'luma-acp-cli-gui.json'
        self.sessions = self.base/'cli-sessions'; self.sessions.mkdir()
        (self.sessions/'synthetic.json').write_text('{"receipt":"unknown","draft":"保留"}')
        self.template = ['/usr/bin/python3','-I','-B',str(self.base/'cli-bridge-v17/main.py'),'{port}',str(self.base/'cli-bridge-token'),str(self.sessions)]
        self.saved = {'pid':777,'stamp':'23','template':self.template,'url':'http://127.0.0.1:45678'}
        self.write()
        self.command = [x.replace('{port}','45678') for x in self.template]
        self.patches = [patch.object(ns['os'],'pidfd_open',return_value=9,create=True),
            patch.object(ns['signal'],'pidfd_send_signal',create=True), patch.object(ns['os'],'close'),
            patch.object(ns['select'],'select',side_effect=[([],[],[]),([9],[],[])])]
        self.open,self.send,self.close,self.poll = [p.start() for p in self.patches]
        for p in self.patches:self.addCleanup(p.stop)
        self.stamp = patch.dict(ns,read_stamp=lambda pid:'23',read_command=lambda pid:self.command)
        self.stamp.start();self.addCleanup(self.stamp.stop)
    def write(self):self.record.write_text(json.dumps(self.saved))
    def run_stop(self,confirmation='confirmed',task='luma-acp-cli-gui'):
        return ns['stop_owned_bridge'](str(self.base),task,confirmation)
    def untouched(self):self.send.assert_not_called()
    def test_cancel_does_not_open_metadata_lock_or_signal(self):
        with self.assertRaises(ValueError):self.run_stop('cancelled')
        self.assertFalse((self.base/'luma-acp-cli-gui.lock').exists());self.open.assert_not_called();self.untouched()
    def test_wrong_service_is_rejected(self):
        with self.assertRaises(ValueError):self.run_stop(task='ShellService')
        self.open.assert_not_called();self.untouched()
    def test_exact_process_handle_is_stopped_without_touching_records(self):
        before=(self.sessions/'synthetic.json').read_text()
        self.assertEqual({'stopped':True},self.run_stop())
        self.send.assert_called_once_with(9,signal.SIGTERM);self.close.assert_called_once_with(9)
        self.assertEqual(before,(self.sessions/'synthetic.json').read_text());self.assertEqual(self.saved,json.loads(self.record.read_text()))
    def test_all_supported_task_identities_reuse_the_exact_owned_process_contract(self):
        for task in ['luma-native-cli-gui','luma-native-cli-gui-v3','luma-antigravity-gui','luma-acp-cli-gui','luma-codebuddy-gui-v1']:
            with self.subTest(task=task):
                (self.base/(task+'.json')).write_text(json.dumps(self.saved))
                self.poll.side_effect=[([],[],[]),([9],[],[])]
                self.send.reset_mock(); self.close.reset_mock()
                self.assertEqual({'stopped':True},self.run_stop(task=task))
                self.send.assert_called_once_with(9,signal.SIGTERM)
                self.close.assert_called_once_with(9)
                self.assertEqual('{"receipt":"unknown","draft":"保留"}',(self.sessions/'synthetic.json').read_text())
    def test_reused_pid_stamp_is_not_signalled(self):
        with patch.dict(ns,read_stamp=lambda pid:'24'):
            with self.assertRaisesRegex(ValueError,'身份已变化'):self.run_stop()
        self.untouched();self.close.assert_called_once_with(9)
    def test_different_command_is_not_signalled(self):
        self.command=['/usr/bin/python3','someone-elses-service.py']
        with self.assertRaisesRegex(ValueError,'身份已变化'):self.run_stop()
        self.untouched()
    def test_wrong_launcher_path_is_rejected_before_opening_pid(self):
        self.saved['template'][3]='/tmp/not-our-service/main.py';self.write()
        with self.assertRaisesRegex(ValueError,'路径身份'):self.run_stop()
        self.open.assert_not_called();self.untouched()
    def test_wrong_token_path_is_not_accepted_as_a_bridge(self):
        self.saved['template'][5]='/tmp/other-token';self.write()
        with self.assertRaises(ValueError):self.run_stop()
        self.open.assert_not_called();self.untouched()
    def test_already_exited_process_allows_same_identity_start_path(self):
        self.open.side_effect=ProcessLookupError()
        self.assertEqual({'alreadyExited':True},self.run_stop());self.untouched()
    def test_exited_pidfd_is_not_signalled(self):
        self.poll.side_effect=[([9],[],[])]
        self.assertEqual({'alreadyExited':True},self.run_stop());self.untouched();self.close.assert_called_once_with(9)
    def test_no_pidfd_support_refuses_unsafe_fallback(self):
        with patch.object(ns['os'],'pidfd_open',None):
            with self.assertRaisesRegex(RuntimeError,'安全锁定'):self.run_stop()
        self.untouched()
    def test_stop_timeout_is_retryable_and_does_not_rewrite_metadata(self):
        self.poll.side_effect=[([],[],[]),([],[],[])]
        with self.assertRaisesRegex(RuntimeError,'未启动新服务'):self.run_stop()
        self.assertEqual(self.saved,json.loads(self.record.read_text()))
        self.poll.side_effect=[([],[],[]),([9],[],[])]
        self.assertEqual({'stopped':True},self.run_stop())
    def test_untrusted_pid_metadata_is_not_signalled(self):
        self.saved['pid']=True;self.write()
        with self.assertRaises(ValueError):self.run_stop()
        self.open.assert_not_called();self.untouched()

if __name__=='__main__':unittest.main(verbosity=2)
