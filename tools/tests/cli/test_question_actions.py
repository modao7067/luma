#!/usr/bin/env python3
from pathlib import Path
import sys,unittest
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).resolve().parents[3]/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.claude import ClaudeSession
from luma_cli.kimi import KimiSession
from luma_cli.grok import GrokSession
from luma_cli.pi import PiSession
from luma_cli.pi_native import PiNativeSession

class Peer:
 def __init__(self):self.sent=[]
 def send(self,frame):self.sent.append(frame)
def session(kind,name):
 s=kind({'cli':name,'nativeId':'native','cwd':'/tmp','receipts':{}},lambda:None);s.peer=Peer();s.current_submission=None
 return s
Q={'question':'Synthetic question?','options':[{'label':'A'},{'label':'B'}]}
class QuestionActions(unittest.TestCase):
 def test_claude_submit_and_true_skip_deny_preserve_native_protocol(self):
  for choice,answers,behavior in [('deny',{},'deny'),('allow',{'0':['A']},'allow')]:
   s=session(ClaudeSession,'Claude Code');s.permission_request({'request_id':'r','request':{'tool_name':'AskUserQuestion','input':{'questions':[Q]}}})
   r=s.data['requests'][0];self.assertEqual((r['submitChoice'],r['skipChoice']),('allow','deny'))
   self.assertEqual(r['title'],'Answer')
   s.answer({'id':'r','choice':choice,'answers':answers})
   result=s.peer.sent[-1]['response']['response'];self.assertEqual(result['behavior'],behavior)
   if choice=='deny':self.assertNotIn('updatedInput',result)
   else:self.assertEqual(result['updatedInput']['answers'],{'Synthetic question?':'A'})
   self.assertEqual(s.data['requests'],[])
 def test_kimi_skip_is_native_decline_not_an_empty_accept(self):
  s=session(KimiSession,'Kimi Code');s.event({'id':4,'method':'elicitation/create','params':{'sessionId':'native','requestedSchema':{'type':'object','properties':{'q':{'type':'string'}},'required':['q']}}})
  r=s.data['requests'][0];self.assertEqual((r['submitChoice'],r['skipChoice']),('accept','decline'))
  self.assertEqual(r['title'],'Answer')
  self.assertEqual(r['choices'][-1],{'id':'cancel','label':'终止提问'})
  s.answer({'id':'4','choice':r['skipChoice'],'answers':{}})
  self.assertEqual(s.peer.sent[-1]['result'],{'action':'decline'})
 def test_grok_skip_preserves_cancelled_outcome(self):
  s=session(GrokSession,'Grok Build');s.loading=False;s.processing_replay=False
  raw={'id':5,'method':'x.ai/ask_user_question','params':{'questions':[Q]}}
  s.event(raw)
  r=s.data['requests'][0];self.assertEqual((r['submitChoice'],r['skipChoice']),('accept','cancel'))
  self.assertEqual(r['title'],'Answer')
  s.answer({'id':'5','choice':'cancel','answers':{}})
  self.assertEqual(s.peer.sent[-1]['result'],{'outcome':'cancelled'})
 def test_pi_rpc_skip_is_the_existing_cancelled_response(self):
  s=session(PiSession,'pi');s.ui_request({'id':'r','method':'input','title':'input'})
  r=s.data['requests'][0];self.assertEqual((r['submitChoice'],r['skipChoice']),('submit','cancel'))
  s.answer({'id':'r','choice':'cancel','answers':{}})
  self.assertEqual(s.peer.sent[-1],{'type':'extension_ui_response','id':'r','cancelled':True})
 def test_pi_sdk_keeps_native_cancellation_value(self):
  s=session(PiNativeSession,'pi');calls=[];s.call=lambda method,data:(calls.append((method,data)) or {'answered':True})
  s.ui_request({'id':'r','method':'input','title':'input'})
  s.answer({'id':'r','choice':'cancel','answers':{}})
  self.assertEqual(calls[0][0],'extension_ui_response')
  self.assertTrue(calls[0][1]['cancelled'])
 def test_pi_question_title_fallback_does_not_replace_custom_or_approval_titles(self):
  for kind,name in [(PiSession,'pi'),(PiNativeSession,'pi')]:
   s=session(kind,name);s.ui_request({'id':'a','method':'input'});self.assertEqual(s.data['requests'][0]['title'],'Answer')
   s.ui_request({'id':'a','method':'input','title':'Project-specific question'});self.assertEqual(s.data['requests'][0]['title'],'Project-specific question')
   s.ui_request({'id':'a','method':'confirm'});self.assertEqual(s.data['requests'][0]['title'],'CLI 请求')
 def test_non_question_permission_shapes_do_not_gain_question_actions(self):
  s=session(ClaudeSession,'Claude Code');s.permission_request({'request_id':'r','request':{'tool_name':'Bash','input':{'command':'synthetic'}}})
  r=s.data['requests'][0];self.assertNotIn('submitChoice',r);self.assertNotIn('skipChoice',r)
if __name__=='__main__':unittest.main()
