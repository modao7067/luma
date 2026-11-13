import copy
import pathlib
import sys
import tempfile
import unittest
from unittest.mock import patch
sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parent))
from test_claude_permissions import session, HELP
from luma_cli.transport import Rejected

CATALOG = [
    {'value': 'default', 'resolvedModel': 'claude-fixture', 'displayName': 'Default', 'supportsEffort': True, 'supportedEffortLevels': ['low', 'high']},
    {'value': 'opus', 'resolvedModel': 'claude-fixture', 'displayName': 'Opus', 'supportsEffort': True, 'supportedEffortLevels': ['low', 'high']},
    {'value': 'legacy', 'resolvedModel': 'legacy-model', 'displayName': 'Legacy'},
]

def ready():
    s, saves = session()
    s.native_models = copy.deepcopy(CATALOG)
    s.update(model='default', effort='low', models=[{'id': m['value'], 'efforts': m.get('supportedEffortLevels', [])} for m in CATALOG])
    return s, saves

class ClaudeEffort(unittest.TestCase):
    def test_new_connection_reads_native_model_and_catalog_before_first_prompt(self):
        s, _ = session(None)
        with tempfile.TemporaryDirectory() as tmp:
            s.history_path = lambda: pathlib.Path(tmp)/'missing'
            s.load_history = lambda: None
            s.start = lambda argv: None
            def call(method, params=None):
                if method == 'initialize': return {'models': CATALOG, 'current_permission_mode': 'default'}
                if method == 'get_settings': return {'applied': {'model': 'claude-fixture', 'effort': 'high'}}
                return {}
            s.call = call
            with patch('luma_cli.claude.subprocess.run') as run:
                run.return_value.returncode = 0; run.return_value.stdout = HELP
                s.connect()
            self.assertEqual(s.data['model'], 'default')
            self.assertEqual(s.data['effort'], 'high')
            self.assertEqual([m['efforts'] for m in s.data['models']], [['low','high'],['low','high'],[]])
            self.assertNotIn('selectedEffort', s.config)

    def test_alias_resolution_keeps_explicit_native_choice(self):
        s, _ = ready()
        self.assertEqual(s.model_id('claude-fixture'), 'default')
        s.config['selectedModel'] = 'opus'
        self.assertEqual(s.model_id('claude-fixture'), 'opus')
        self.assertEqual(s.model_id('custom-provider-model'), 'custom-provider-model')

    def test_effort_write_requires_applied_readback_before_persisting(self):
        s, saves = ready(); calls=[]
        def call(method, params=None):
            calls.append((method,params)); self.assertEqual(saves, [])
            self.assertNotIn('selectedEffort', s.config)
            return {'applied': {'model': 'claude-fixture','effort':'high'}} if method=='get_settings' else {}
        s.call=call
        s.configure({'model':'default','effort':'high'})
        self.assertEqual(calls, [('apply_flag_settings',{'settings':{'effortLevel':'high'}}),('get_settings',None)])
        self.assertEqual(s.data['effort'],'high'); self.assertEqual(saves[-1]['selectedEffort'],'high')

    def test_native_clamping_is_visible_and_not_persisted_as_requested(self):
        s,saves=ready()
        s.call=lambda method,params=None: {'applied':{'model':'claude-fixture','effort':'low'}} if method=='get_settings' else {}
        with self.assertRaisesRegex(Rejected,'原生当前值：low'): s.configure({'effort':'high'})
        self.assertEqual(s.data['effort'],'low'); self.assertEqual(saves,[])

    def test_invalid_target_effort_rejects_before_changing_model(self):
        s,saves=ready(); s.call=lambda *a:self.fail('Must not dispatch')
        with self.assertRaises(Rejected): s.configure({'model':'legacy','effort':'high'})
        self.assertEqual(s.data['model'],'default'); self.assertEqual(saves,[])

    def test_rejected_write_keeps_confirmed_level_but_lost_write_is_unknown(self):
        for error in [Rejected('native policy'),TimeoutError('lost reply')]:
            s,saves=ready()
            def call(*args): raise error
            s.call=call
            with self.assertRaises(type(error)): s.configure({'effort':'high'})
            self.assertEqual(s.data['effort'],'low' if isinstance(error,Rejected) else '')
            self.assertEqual(saves,[])

    def test_missing_applied_readback_is_not_success(self):
        s,saves=ready(); s.call=lambda *a:{}
        with self.assertRaises(Rejected): s.configure({'effort':'high'})
        self.assertEqual(saves,[])

    def test_save_failure_reports_live_value_and_keeps_previous_saved_level(self):
        s,_=ready(); s.config['selectedEffort']='low'
        s.call=lambda method,params=None: {'applied':{'effort':'high'}} if method=='get_settings' else {}
        def fail(): raise OSError('disk full')
        s.save=fail
        with self.assertRaisesRegex(RuntimeError,'保存失败'): s.configure({'effort':'high'})
        self.assertEqual(s.data['effort'],'high'); self.assertEqual(s.config['selectedEffort'],'low')

    def test_older_cli_keeps_model_permission_connection_without_effort(self):
        s,_=ready()
        def unavailable(*a): raise Rejected('Unsupported control request: get_settings')
        s.call=unavailable
        self.assertFalse(s.read_settings(optional=True))
        with self.assertRaises(Rejected): s.read_settings()
        self.assertEqual(s.data['model'],'default')

    def test_switch_to_model_without_effort_clears_only_session_override(self):
        s,saves=ready(); s.config['selectedEffort']='high'; calls=[]
        def call(method,params=None):
            calls.append((method,params))
            if method=='set_model': return {'model':'legacy-model'}
            if method=='get_settings': return {'applied':{'model':'legacy-model','effort':None}}
            return {}
        s.call=call; s.configure({'model':'legacy'})
        self.assertEqual(s.data['model'],'legacy'); self.assertEqual(s.data['effort'],'')
        self.assertNotIn('selectedEffort',saves[-1])
        self.assertIn(('apply_flag_settings',{'settings':{'effortLevel':None}}),calls)
        self.assertNotIn('update_settings',[c[0] for c in calls])

    def test_init_event_matches_picker_and_projects_native_effort(self):
        s,_=ready()
        s.event({'type':'system','subtype':'init','model':'claude-fixture','effort':'high','permissionMode':'auto'})
        self.assertEqual(s.data['model'],'default'); self.assertEqual(s.data['effort'],'high'); self.assertEqual(s.data['permission'],'auto')

if __name__=='__main__': unittest.main(verbosity=2)
