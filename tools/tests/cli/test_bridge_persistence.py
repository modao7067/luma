import copy,json,os,pathlib,sys,tempfile,threading,time,unittest
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.base import Session,DeliveryPersistenceError
from luma_cli.transport import Rejected

class Disk:
 def __init__(self,root):
  self.path=pathlib.Path(root)/'session.json';self.config=None;self.attempts=0;self.fail=None;self.after_commit=False;self.commits=0
 def save(self):
  self.attempts+=1;fault=bool(self.fail and self.fail(self.config))
  if fault and not self.after_commit:raise OSError('PRIVATE_FIXTURE_SENTINEL must not enter status/logs')
  target=self.path.with_suffix('.tmp')
  with target.open('w') as f:json.dump(self.config,f);f.flush();os.fsync(f.fileno())
  os.replace(target,self.path)
  directory=os.open(self.path.parent,os.O_RDONLY|os.O_DIRECTORY)
  try:os.fsync(directory)
  finally:os.close(directory)
  self.commits+=1
  if fault:raise OSError('PRIVATE_FIXTURE_SENTINEL after replace')
 def read(self):return json.loads(self.path.read_text())

class Carrier(Session):
 def __init__(self,disk,config=None,correlated=False):
  config=config if config is not None else {'cli':'fixture','cwd':str(disk.path.parent),'nativeId':'native','ownerDirectory':str(disk.path.parent/'owner')}
  self.sent=[];self.behavior=lambda p:True;self.boundary=[];self.disk=disk;disk.config=config
  super().__init__(config,disk.save);self.correlated_delivery=correlated
 def prompt(self,p):
  native=self.disk.read();d=native['deliveries'][p['submissionId']]
  assert d['state']=='dispatching' and native['receipts'][p['submissionId']]=='unknown'
  self.boundary.append({'id':p['submissionId'],'durableState':d['state'],'receipt':native['receipts'][p['submissionId']]})
  self.sent.append(p['submissionId']);return self.behavior(p)

def payload(id):return {'submissionId':id,'text':'same text','steer':False}
def state(config,id='a'):return config.get('deliveries',{}).get(id,{}).get('state')
def wait_for(test):
 end=time.monotonic()+2
 while not test():
  if time.monotonic()>end:raise AssertionError('condition timed out')
  time.sleep(.003)

class Persistence(unittest.TestCase):
 def setUp(self):
  self.tmp=tempfile.TemporaryDirectory(prefix='luma-bridge-store-');self.disk=Disk(self.tmp.name);self.s=Carrier(self.disk)
 def tearDown(self):
  self.s.update(connected=False);self.tmp.cleanup()
 def fault(self,predicate,after=False):self.disk.fail=predicate;self.disk.after_commit=after
 def prepare(self):self.s.admit(payload('a'))
 def fail_until_paused(self):
  self.s.update(connected=True);wait_for(lambda:self.s.delivery_storage_error is not None)
  with self.s.operation:pass
  if any(d['state']=='queued' for d in self.s.config.get('deliveries',{}).values()):
   self.assertTrue(self.s.delivery_worker.is_alive())
  else:wait_for(lambda:self.s.delivery_worker is None)
  self.assertNotIn('PRIVATE_FIXTURE_SENTINEL',json.dumps(self.s.snapshot()))
  log=pathlib.Path(self.s.config['ownerDirectory'])/'native-owner.log'
  self.assertNotIn('PRIVATE_FIXTURE_SENTINEL',log.read_text())
  count=self.disk.attempts
  for _ in range(3):self.s.update(running=False)
  time.sleep(.02);self.assertEqual(count,self.disk.attempts,'updates must not become save retry loop')
 def recover(self,expected_error=None):
  self.disk.fail=None;self.s.action('refresh',{})
  self.assertIsNone(self.s.delivery_storage_error)
  self.assertEqual(expected_error,self.s.snapshot()['error'])
 def test_claim_before_commit_and_after_replace_never_cross_boundary_until_durable_recovery(self):
  for after in (False,True):
   with self.subTest(after_replace=after):
    if after:self.tearDown();self.setUp()
    self.prepare();self.fault(lambda c:state(c)=='dispatching',after);self.fail_until_paused()
    self.assertFalse(self.s.sent);self.assertEqual(state(self.s.config),'queued');self.assertNotIn('a',self.s.config.get('receipts',{}))
    self.assertEqual(state(self.disk.read()),'dispatching' if after else 'queued')
    self.recover();wait_for(lambda:state(self.s.config)=='accepted')
    with self.s.operation:pass
    self.assertEqual(self.s.sent,['a']);self.assertEqual(state(self.disk.read()),'accepted')
 def test_settlement_failure_keeps_unknown_and_never_reexecutes_on_refresh_or_restart(self):
  for after in (False,True):
   with self.subTest(after_replace=after):
    if after:self.tearDown();self.setUp()
    self.s.behavior=lambda p:False;self.prepare();self.fault(lambda c:state(c)=='unknown',after);self.fail_until_paused()
    self.assertEqual(self.s.sent,['a']);self.assertEqual(state(self.s.config),'unknown')
    self.assertEqual(state(self.disk.read()),'unknown' if after else 'dispatching')
    self.recover();time.sleep(.02);self.assertEqual(self.s.sent,['a'])
    restored=Carrier(self.disk,self.disk.read());self.s.update(connected=False);self.s=restored
    restored.update(connected=True);restored.start_delivery_worker();time.sleep(.02)
    self.assertFalse(restored.sent);self.assertEqual(state(restored.config),'unknown')
    restored.admit(payload('b'));time.sleep(.01);self.assertFalse(restored.sent)
    restored.receipt('a','accepted');wait_for(lambda:restored.sent==['b'])
    self.assertEqual(restored.config['receipts']['a'],'accepted')
 def test_native_acceptance_stays_true_when_its_checkpoint_fails_and_is_not_replayed(self):
  self.prepare();self.fault(lambda c:state(c)=='accepted');self.fail_until_paused()
  self.assertEqual(self.s.sent,['a']);self.assertEqual(self.s.snapshot()['receipts']['a'],'accepted')
  self.assertEqual(state(self.disk.read()),'dispatching')
  self.recover();self.assertEqual(state(self.disk.read()),'accepted');time.sleep(.02);self.assertEqual(self.s.sent,['a'])
 def test_native_rejection_checkpoint_failure_does_not_break_scheduler_or_resend(self):
  self.s.behavior=lambda p:(_ for _ in ()).throw(Rejected('native fixture refused'))
  self.prepare();self.fault(lambda c:state(c)=='rejected');self.fail_until_paused()
  self.assertEqual(self.s.sent,['a']);self.assertEqual(self.s.snapshot()['receipts']['a'],'rejected')
  self.recover(expected_error='native fixture refused');self.assertEqual(state(self.disk.read()),'rejected');self.assertEqual(self.s.sent,['a'])
 def test_pre_dispatch_validation_rejection_is_not_published_if_its_save_fails(self):
  self.prepare();self.s.update(model='changed');self.fault(lambda c:state(c)=='rejected');self.fail_until_paused()
  self.assertFalse(self.s.sent);self.assertEqual(state(self.s.config),'queued');self.assertNotIn('a',self.s.snapshot()['receipts'])
  self.recover();wait_for(lambda:state(self.s.config)=='rejected');self.assertFalse(self.s.sent)
 def test_restart_claim_failure_is_conservative_even_if_replace_succeeded(self):
  for after in (False,True):
   with self.subTest(after_replace=after):
    if after:self.tearDown();self.setUp()
    self.prepare();self.fault(lambda c:state(c)=='dispatching',after);self.fail_until_paused()
    durable=self.disk.read();self.disk.fail=None;self.s.update(connected=False)
    restored=Carrier(self.disk,durable);self.s=restored;restored.update(connected=True);restored.start_delivery_worker()
    if after:
     time.sleep(.02);self.assertFalse(restored.sent);self.assertEqual(state(restored.config),'unknown')
    else:wait_for(lambda:restored.sent==['a'])
 def test_explicit_failed_refresh_attempt_is_bounded_and_does_not_clear_fault(self):
  self.prepare();self.fault(lambda c:state(c)=='dispatching');self.fail_until_paused()
  self.fault(lambda c:True);before=self.disk.attempts
  with self.assertRaises(DeliveryPersistenceError):self.s.action('refresh',{})
  time.sleep(.02);self.assertEqual(self.disk.attempts,before+1);self.assertFalse(self.s.sent)
  self.assertIn('保存失败',self.s.snapshot()['error']);self.assertTrue(self.s.delivery_worker.is_alive())
 def test_real_async_receipt_keeps_reader_safe_and_explicit_refresh_recovers_without_replaying(self):
  self.s.behavior=lambda p:False;self.prepare();self.s.update(connected=True);wait_for(lambda:state(self.s.config)=='unknown')
  self.fault(lambda c:state(c)=='accepted')
  self.s.receipt('a','accepted')
  wait_for(lambda:self.s.delivery_worker is None);self.assertEqual(self.s.sent,['a'])
  self.disk.fail=None;before=self.disk.attempts;self.s.receipt('a','accepted')
  self.assertEqual(before,self.disk.attempts);self.assertIsNotNone(self.s.delivery_storage_error)
  self.s.action('refresh',{});self.assertIsNone(self.s.delivery_storage_error)
  self.assertEqual(state(self.disk.read()),'accepted');self.assertEqual(self.s.sent,['a'])
 def test_correlated_mode_accepts_new_ids_but_never_resends_unknown_after_recovery(self):
  self.s.correlated_delivery=True;self.s.behavior=lambda p:False;self.prepare();self.fault(lambda c:state(c)=='unknown');self.fail_until_paused()
  self.recover();self.s.behavior=lambda p:True;self.s.admit(payload('b'));wait_for(lambda:self.s.sent==['a','b'])
  self.assertEqual(self.s.config['receipts']['a'],'unknown')
 def test_failed_admission_and_cancel_remain_before_native_boundary(self):
  self.fault(lambda c:True)
  with self.assertRaises(DeliveryPersistenceError):self.s.admit(payload('a'))
  self.assertFalse(self.s.sent);self.assertNotIn('a',self.s.config['deliveries']);self.assertIn('保存失败',self.s.snapshot()['error'])
  self.disk.fail=None;self.prepare();self.fault(lambda c:state(c)=='cancelled')
  with self.assertRaises(DeliveryPersistenceError):self.s.cancel_pending('a')
  self.assertEqual(state(self.s.config),'queued');self.assertFalse(self.s.sent)
  self.disk.fail=None;self.s.cancel_pending('a');self.assertEqual(state(self.disk.read()),'cancelled');self.assertIsNone(self.s.delivery_storage_error)

if __name__=='__main__':unittest.main(verbosity=2)
