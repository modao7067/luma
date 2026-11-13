#!/usr/bin/env python3
import copy, pathlib, sys, threading, time, unittest
sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.base import Session
from luma_cli.transport import Rejected

def wait_for(predicate):
    deadline = time.monotonic() + 3
    while not predicate():
        if time.monotonic() > deadline: raise AssertionError('delivery did not settle')
        time.sleep(.005)

class Carrier(Session):
    def __init__(self, config=None):
        self.saved = []; self.sent = []; self.behavior = lambda p: True
        super().__init__(config or {'cli': 'fake', 'cwd': '/tmp'}, lambda: self.saved.append(copy.deepcopy(self.config)))
        self.update(connected=True, nativeId='native', capabilities={})
    def prompt(self, payload):
        self.sent.append(copy.deepcopy(payload)); self.update(running=True)
        return self.behavior(payload)

def payload(id, text=None): return {'submissionId': id, 'text': text or id, 'steer': False}

class ReliableDeliveryTest(unittest.TestCase):
    def test_host_ack_precedes_native_work_and_is_not_native_accepted(self):
        s = Carrier(); s.update(running=True)
        self.assertEqual(s.admit(payload('a')), {'hostAdmitted': True, 'deliveryState': 'queued'})
        self.assertNotIn('a', s.config.get('receipts', {})); self.assertFalse(s.sent)
        self.assertEqual(s.saved[-1]['deliveries']['a']['state'], 'queued')
        s.update(running=False); wait_for(lambda: s.config.get('receipts', {}).get('a') == 'accepted')
        self.assertEqual(len(s.sent), 1)
    def test_unsupported_steer_queues_fifo_without_interrupting(self):
        s = Carrier(); s.update(running=True)
        s.admit(payload('a')); s.admit(payload('b'))
        self.assertEqual([x['id'] for x in s.snapshot()['hostQueue']], ['a', 'b'])
        s.update(running=False); wait_for(lambda: len(s.sent) == 1)
        self.assertEqual(s.sent[0]['submissionId'], 'a'); self.assertFalse(s.sent[0]['steer'])
        s.update(running=False); wait_for(lambda: len(s.sent) == 2)
        self.assertEqual(s.sent[1]['submissionId'], 'b')
    def test_native_steer_preferred(self):
        s = Carrier(); s.update(running=True, capabilities={'steer': True})
        s.admit(payload('a')); wait_for(lambda: len(s.sent) == 1)
        self.assertTrue(s.sent[0]['steer'])
    def test_queue_first_host_queue_supports_explicit_steer_pending_out_of_order(self):
        class QueueFirst(Carrier):
            busy_default_steer = False
        s = QueueFirst(); s.update(running=True, capabilities={'steer': True})
        s.admit(payload('first')); s.admit(payload('second'))
        time.sleep(.02); self.assertFalse(s.sent)
        self.assertEqual([x['id'] for x in s.snapshot()['hostQueue']], ['first', 'second'])
        self.assertTrue(all(x['canSteer'] and x['canEdit'] and x['canRemove'] for x in s.snapshot()['hostQueue']))
        s.action('steer-pending', {'id': 'second'})
        wait_for(lambda: len(s.sent) == 1)
        self.assertEqual(s.sent[0]['submissionId'], 'second'); self.assertTrue(s.sent[0]['steer'])
        self.assertEqual([x['id'] for x in s.snapshot()['hostQueue']], ['first'])
        s.update(running=False); wait_for(lambda: len(s.sent) == 2)
        self.assertEqual(s.sent[1]['submissionId'], 'first'); self.assertFalse(s.sent[1]['steer'])
    def test_unknown_never_replayed_and_does_not_block_new_host_admission(self):
        s = Carrier(); s.behavior = lambda p: False
        s.admit(payload('a')); wait_for(lambda: s.config['deliveries']['a']['state'] == 'unknown')
        s.admit(payload('a')); s.admit(payload('b'))
        self.assertEqual(len(s.sent), 1)
        self.assertEqual(s.config['deliveries']['b']['state'], 'queued')
        restored = Carrier(copy.deepcopy(s.saved[-1])); restored.start_delivery_worker()
        time.sleep(.03); self.assertFalse(restored.sent)
        restored.receipt('a', 'accepted'); wait_for(lambda: len(restored.sent) == 1)
        self.assertEqual(restored.sent[0]['submissionId'], 'b')
    def test_received_but_http_reply_lost_same_id_is_deduplicated(self):
        s = Carrier(); p = payload('a'); s.admit(p)
        wait_for(lambda: s.config.get('receipts', {}).get('a') == 'accepted')
        self.assertEqual(s.admit(p)['deliveryState'], 'accepted'); self.assertEqual(len(s.sent), 1)
        with self.assertRaises(Rejected): s.admit(payload('a', 'different'))
    def test_cancel_before_claim_prevents_dispatch(self):
        s = Carrier(); s.update(running=True); s.admit(payload('a'))
        s.cancel_pending('a'); s.update(running=False); time.sleep(.02)
        self.assertFalse(s.sent); self.assertEqual(s.snapshot()['hostQueue'], [])
    def test_cancel_after_claim_cannot_retract_native_write(self):
        s = Carrier(); s.behavior = lambda p: False; s.admit(payload('a'))
        wait_for(lambda: len(s.sent) == 1)
        with self.assertRaises(Rejected): s.cancel_pending('a')
        self.assertEqual(len(s.sent), 1)
    def test_cancel_and_dispatch_race_has_exactly_one_owner(self):
        for number in range(30):
            s = Carrier(); s.update(running=True); s.admit(payload('race'))
            barrier = threading.Barrier(2); cancelled = []
            def cancel():
                barrier.wait()
                try: s.cancel_pending('race'); cancelled.append(True)
                except Rejected: cancelled.append(False)
            thread = threading.Thread(target=cancel); thread.start()
            barrier.wait(); s.update(running=False); thread.join()
            if cancelled[0]:
                time.sleep(.005); self.assertFalse(s.sent)
            else:
                wait_for(lambda: len(s.sent) == 1)
            self.assertLessEqual(len(s.sent), 1)
    def test_save_failure_does_not_claim_host_admission(self):
        s = Carrier(); s.save = lambda: (_ for _ in ()).throw(OSError('disk full'))
        with self.assertRaises(OSError): s.admit(payload('a'))
        self.assertFalse(s.sent); self.assertNotIn('a', s.config['deliveries'])
    def test_settings_cannot_change_queued_payload_and_rejection_is_definitive(self):
        s = Carrier(); s.update(running=True); s.admit(payload('a'))
        with self.assertRaises(Rejected): s.action('configure', {'model': 'other'})
        s.update(model='externally-changed', running=False)
        wait_for(lambda: s.config.get('receipts', {}).get('a') == 'rejected')
        self.assertFalse(s.sent)
    def test_dispatch_marker_survives_crash_without_reexecution(self):
        s = Carrier({'cli': 'fake', 'cwd': '/tmp', 'receipts': {'a': 'unknown'},
                     'deliveries': {'a': {'state': 'dispatching', 'payload': payload('a'), 'settings': {}}}})
        s.start_delivery_worker(); time.sleep(.02)
        self.assertEqual(s.config['deliveries']['a']['state'], 'unknown'); self.assertFalse(s.sent)
    def test_execution_failure_cannot_regress_native_acceptance(self):
        s = Carrier(); s.admit(payload('a'))
        wait_for(lambda: s.config.get('receipts', {}).get('a') == 'accepted')
        s.receipt('a', 'rejected'); s.receipt('a', 'unknown')
        self.assertEqual(s.config['receipts']['a'], 'accepted')
        self.assertEqual(s.config['deliveries']['a']['state'], 'accepted')
    def test_native_pending_over_thirty_seconds_keeps_task_and_next_send(self):
        s = Carrier(); s.behavior = lambda p: False
        s.admit(payload('slow')); wait_for(lambda: len(s.sent) == 1)
        time.sleep(30.1)
        self.assertEqual(s.config['receipts']['slow'], 'unknown')
        s.admit(payload('next'))
        self.assertEqual(len(s.sent), 1)
        s.receipt('slow', 'accepted'); s.update(running=False)
        wait_for(lambda: len(s.sent) == 2)
        self.assertEqual(s.sent[-1]['submissionId'], 'next')
    def test_legacy_send_contract_keeps_native_receipt(self):
        s = Carrier(); self.assertEqual(s.submit(payload('old')), {'receipt': 'accepted'})
        self.assertNotIn('hostAdmitted', s.submit(payload('old')))

if __name__ == '__main__': unittest.main()
