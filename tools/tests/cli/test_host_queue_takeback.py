#!/usr/bin/env python3
import copy
import threading
import time
import unittest
from test_reliable_delivery import Carrier, payload, wait_for
from luma_cli.transport import Rejected

def expected(p):
    return {key: p.get(key, [] if key != 'text' else '') for key in ('text', 'images', 'skills')}

class HostQueueTakebackTest(unittest.TestCase):
    def queued(self):
        s = Carrier(); s.update(running=True)
        p = {**payload('id', 'original\n中文'), 'images': ['/image.png'], 'skills': ['skill']}
        s.admit(p)
        return s, p

    def test_edit_capability_and_exact_payload_proof_precede_any_native_write(self):
        s, p = self.queued()
        self.assertTrue(s.snapshot()['hostQueue'][0]['canEdit'])
        s.action('take-back', {'id': 'id', 'operationId': 'op', 'expectedPayload': expected(p)})
        proof = s.snapshot()['takebacks']['id']
        self.assertEqual(proof, {'operationId': 'op', 'payload': p})
        self.assertEqual(s.saved[-1]['deliveries']['id']['takebackId'], 'op')
        s.update(running=False); time.sleep(.015)
        self.assertFalse(s.sent); self.assertEqual(s.snapshot()['hostQueue'], [])

    def test_lost_ack_and_process_reload_keep_the_same_idempotent_proof(self):
        s, p = self.queued(); s.take_back('id', 'op', expected(p))
        restored = Carrier(copy.deepcopy(s.saved[-1]))
        self.assertEqual(restored.take_back('id', 'op', expected(p)), {'takenBack': True})
        restored.start_delivery_worker(); time.sleep(.015)
        self.assertFalse(restored.sent)
        self.assertEqual(restored.snapshot()['takebacks']['id']['operationId'], 'op')
        with self.assertRaises(Rejected): restored.take_back('id', 'another', expected(p))

    def test_different_payload_cannot_cancel_or_rebind_the_original_identity(self):
        s, p = self.queued()
        with self.assertRaises(Rejected): s.take_back('id', 'op', {**expected(p), 'text': 'other'})
        self.assertEqual(s.config['deliveries']['id']['state'], 'queued')
        self.assertEqual(s.snapshot()['takebacks'], {})

    def test_takeback_save_failure_rolls_back_claim_and_exposes_no_proof(self):
        s, p = self.queued(); s.save = lambda: (_ for _ in ()).throw(OSError('disk full'))
        with self.assertRaises(OSError): s.take_back('id', 'op', expected(p))
        self.assertEqual(s.config['deliveries']['id']['state'], 'queued')
        self.assertNotIn('takebackId', s.config['deliveries']['id'])
        self.assertEqual(s.snapshot()['takebacks'], {}); self.assertFalse(s.sent)

    def test_dispatch_winning_cannot_be_taken_back_or_replayed(self):
        s = Carrier(); s.behavior = lambda p: False; p = payload('claimed'); s.admit(p)
        wait_for(lambda: s.config['deliveries']['claimed']['state'] == 'unknown')
        with self.assertRaises(Rejected): s.take_back('claimed', 'op', expected(p))
        self.assertEqual(len(s.sent), 1); self.assertEqual(s.snapshot()['takebacks'], {})

    def test_generic_cancel_does_not_manufacture_takeback_proof(self):
        s, p = self.queued(); s.cancel_pending('id')
        with self.assertRaises(Rejected): s.take_back('id', 'op', expected(p))
        self.assertEqual(s.snapshot()['takebacks'], {})

    def test_takeback_and_dispatch_compete_under_the_same_durable_claim_lock(self):
        for _ in range(20):
            s = Carrier(); s.update(running=True); p = payload('race'); s.admit(p)
            barrier = threading.Barrier(2); result = []
            def take():
                barrier.wait()
                try: s.take_back('race', 'op', expected(p)); result.append(True)
                except Rejected: result.append(False)
            t = threading.Thread(target=take); t.start(); barrier.wait(); s.update(running=False); t.join()
            if result[0]:
                time.sleep(.005); self.assertFalse(s.sent)
                self.assertIn('race', s.snapshot()['takebacks'])
            else:
                wait_for(lambda: len(s.sent) == 1)
                self.assertEqual(s.snapshot()['takebacks'], {})
            self.assertLessEqual(len(s.sent), 1)

if __name__ == '__main__': unittest.main()
