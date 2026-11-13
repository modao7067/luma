#!/usr/bin/env python3
import json,pathlib,sys,unittest
sys.dont_write_bytecode=True
ROOT=pathlib.Path(__file__).resolve().parents[3]
sys.path.insert(0,str(ROOT/'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.claude import ClaudeSession
from luma_cli.acp import AcpSession

class Agents(unittest.TestCase):
    def claude(self):
        s=ClaudeSession({'cli':'Claude Code','cwd':'/tmp','nativeId':'parent'},lambda:None)
        s.current_submission=None;s.stream_id=None;s.stream_blocks={};s.finalized_blocks=set()
        return s
    def event(self,ident,status=None,subtype='task_started',**kw):
        return {'type':'system','subtype':subtype,'session_id':'parent','task_id':ident,
                **({'task_type':'local_agent','tool_use_id':ident,'description':'Native '+ident} if subtype=='task_started' else {}),
                **({'status':status} if status else {}),**kw}
    def test_claude_two_agents_keep_independent_native_lifecycle(self):
        s=self.claude()
        for ident in ('one','two'):s.event(self.event(ident))
        self.assertEqual([a['status'] for a in s.snapshot()['subAgents']],['running','running'])
        s.event(self.event('one','completed','task_notification',summary='native result'))
        self.assertEqual([a['status'] for a in s.snapshot()['subAgents']],['succeeded','running'])
        s.event(self.event('two',subtype='task_updated',patch={'status':'killed'}))
        self.assertEqual(s.subagents['two']['status'],'cancelled')
        self.assertEqual(s.subagents['one']['itemId'],'tool-one')
        s.event(self.event('one','failed','task_notification',tool_use_id='later'))
        self.assertEqual(s.subagents['one']['itemId'],'tool-one')
        self.assertEqual(len(s.snapshot()['subAgents']),2)
    def test_claude_shell_workflow_and_foreign_session_are_not_agents(self):
        s=self.claude()
        for typ in ('local_bash','local_workflow','monitor_mcp','in_process_teammate',None):
            s.event(self.event(str(typ),task_type=typ))
        s.event(self.event('foreign',session_id='other'))
        s.event(self.event('unrecognized','completed','task_notification'))
        self.assertEqual(s.snapshot()['subAgents'],[])
    def test_claude_agent_failure_and_unsupported_pause_remain_distinct(self):
        s=self.claude();s.event(self.event('one'))
        s.event(self.event('one',subtype='task_updated',patch={'status':'paused'}))
        self.assertEqual(s.subagents['one']['status'],'unknown')
        s.event(self.event('one',subtype='task_updated',patch={'status':'failed','error':'native error'}))
        self.assertEqual(s.subagents['one']['status'],'failed');self.assertEqual(s.subagents['one']['detail'],'native error')
        s.exited(ConnectionError('disconnect'))
        self.assertEqual(s.subagents['one']['status'],'failed')
    def test_nested_claude_stream_cannot_overwrite_parent_blocks(self):
        s=self.claude()
        s.event({'type':'stream_event','event':{'type':'message_start','message':{'id':'main'}}})
        s.event({'type':'stream_event','parent_tool_use_id':'launch','event':{'type':'message_start','message':{'id':'child'}}})
        self.assertEqual(s.stream_id,'main')
        s.event({'type':'assistant','parent_tool_use_id':'launch','uuid':'child-entry','message':{'role':'assistant','content':[{'type':'text','text':'child detail'}]}})
        self.assertEqual(s.rows,{})
    def test_acp_generic_tool_text_does_not_create_fake_agents(self):
        s=AcpSession({'cli':'DeepSeek Harness','cwd':'/tmp'},lambda:None);s.chunk_id=None;s.chunk_kind=None;s.current_submission=None
        s.event({'method':'session/update','params':{'update':{'sessionUpdate':'tool_call','toolCallId':'tool','title':'spawn subagent','kind':'other','rawInput':{'description':'Agent running'},'status':'in_progress'}}})
        self.assertEqual(s.snapshot()['subAgents'],[])
        self.assertEqual(s.rows['tool-tool']['text'],'spawn subagent')

if __name__=='__main__':unittest.main(verbosity=2)
