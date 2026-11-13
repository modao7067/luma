#!/usr/bin/env python3
import base64
import pathlib
import sys
import tempfile
import unittest

sys.dont_write_bytecode = True
sys.path.insert(0, str(pathlib.Path(__file__).resolve().parents[3] / 'apps/gui/app/src/main/assets/cli_bridge'))
from luma_cli.base import Session
from luma_cli.media import media_blocks, display_content
from luma_cli.acp import AcpSession
from luma_cli.pi import PiSession
from luma_cli.claude import ClaudeSession
from luma_cli.grok import GrokSession
from luma_cli.kimi import KimiSession
from luma_cli.qoder import QoderSession
from luma_cli.dsh import DshSession
from luma_cli.antigravity import AntigravitySession
from luma_cli.pi_native import PiNativeSession

IMAGE = {"type":"image", "mimeType":"image/png", "data":base64.b64encode(b'png fixture').decode()}
FILE = {"type":"resource_link", "uri":"file:///workspace/中文%20文件.pdf", "name":"中文 文件.pdf", "mimeType":"application/pdf", "size":123}
WRAP = {"type":"content", "content":IMAGE}

def config(cli='fixture', **extra):
    return {'cli':cli, 'cwd':'/child 中文', **extra}

class MediaProjectionTest(unittest.TestCase):
    def test_protocol_shapes_and_text_does_not_sweep_paths(self):
        values = [IMAGE, FILE, {'type':'audio','mimeType':'audio/wav','data':'AA=='},
                  {'type':'image','source':{'type':'base64','media_type':'image/jpeg','data':'AA=='}},
                  {'type':'image_url','image_url':{'url':'https://example.test/a.png'}},
                  {'type':'resource','resource':{'uri':'memo.txt','mimeType':'text/plain','text':'中文'}},
                  {'type':'resource','resource':{'uri':'x.bin','mimeType':'application/octet-stream','blob':'AA=='}},
                  {'type':'file','path':'relative file.zip'}]
        self.assertEqual(len(media_blocks(values)), 8)
        self.assertEqual(media_blocks(FILE)[0]['sizeBytes'], 123)
        self.assertEqual(media_blocks({'type':'text','text':'wrote /tmp/p.png'}), [])
        self.assertEqual(media_blocks({'command':'touch /tmp/p.png'}), [])
        self.assertEqual(display_content([WRAP, {'type':'text','text':'done'}]), 'done')
        self.assertNotIn(IMAGE['data'], display_content(WRAP))

    def test_acp_live_and_load_replay_keep_image_only_chunks_and_tool_resources(self):
        for cls in (DshSession,KimiSession,QoderSession,AntigravitySession):
            s=cls(config(cls.__name__),lambda:None);s.chunk_kind=None;s.chunk_id=None;s.current_submission=None
            s.replaying_history=False;s.replayed_rows=set()
            for kind in ('user_message_chunk','agent_message_chunk'):
                s.event({'method':'session/update','params':{'update':{'sessionUpdate':kind,'content':IMAGE}}})
                self.assertTrue(s.rows[s.chunk_id]['media'])
                self.assertEqual(s.rows[s.chunk_id]['text'],'')
            s.event({'method':'session/update','params':{'update':{'sessionUpdate':'tool_call','toolCallId':'read','content':[WRAP,{'type':'content','content':FILE}]}}})
            row=s.rows['tool-read'];self.assertEqual(len(row['media']),2);self.assertEqual(row['outcome'],'')
            self.assertNotIn(IMAGE['data'],row['outcome'])

    def test_pi_history_live_and_tool_results(self):
        s=PiSession(config('pi'),lambda:None)
        s.project({'id':'user','role':'user','content':[{'type':'text','text':'look'},IMAGE]},'fallback')
        self.assertTrue(s.rows['user']['media'])
        s.project({'id':'reply','role':'assistant','content':[IMAGE]},'fallback')
        self.assertTrue(s.rows['reply-0']['media'])
        s.project({'role':'toolResult','toolCallId':'t','toolName':'img','content':[IMAGE,FILE]},'fallback')
        self.assertEqual(len(s.rows['tool-t']['media']),2)
        s.event({'type':'tool_execution_end','toolCallId':'live','result':{'content':[IMAGE]},'toolName':'image'})
        self.assertTrue(s.rows['tool-live']['media'])
        self.assertEqual(s.rows['tool-live']['outcome'],'')

    def test_claude_human_provenance_and_tool_result(self):
        s=ClaudeSession(config('Claude Code'),lambda:None)
        e={'type':'user','uuid':'u','message':{'role':'user','content':[{'type':'image','source':{'type':'base64','media_type':'image/png','data':'AA=='}}]}}
        s.project(e);self.assertTrue(s.rows['u:0']['media'])
        s.project({'type':'user','uuid':'tool','message':{'role':'user','content':[{'type':'tool_result','tool_use_id':'a','content':[IMAGE,FILE]}]}})
        self.assertEqual(len(s.rows['tool-a']['media']),2)
        s.project({'type':'user','uuid':'notify','isMeta':True,'message':{'role':'user','content':[IMAGE]}})
        self.assertNotIn('notify:0',s.rows)

    def test_grok_native_extension_keeps_image_only_chunks_and_native_resources(self):
        s=GrokSession(config('Grok Build'),lambda:None)
        s.loading=False;s.processing_replay=False;s.seen_events=set();s.chunk_prompt=None
        s.chunk_kind=None;s.chunk_id=None;s.current_submission=None;s.background_tools={};s.child_sessions={}
        s.event({'method':'x.ai/session/update','params':{'update':{'sessionUpdate':'agent_message_chunk','content':IMAGE}}})
        self.assertTrue(s.rows[s.chunk_id]['media']);self.assertEqual(s.rows[s.chunk_id]['text'],'')
        s.event({'method':'x.ai/session/update','params':{'update':{'sessionUpdate':'tool_call','toolCallId':'a','content':[WRAP]}}})
        self.assertTrue(s.rows['tool-a']['media'])

    def test_pi_native_submission_identity_keeps_text_and_image_in_one_row(self):
        s=PiNativeSession(config('pi'),lambda:None)
        s.event({'type':'message_end','_submissionId':'submission','message':{'role':'user','content':[{'type':'text','text':'look'},IMAGE]}})
        self.assertEqual(len(s.rows),1);self.assertEqual(s.rows['submission']['text'],'look');self.assertTrue(s.rows['submission']['media'])

    def test_claude_final_native_image_replaces_its_stream_block(self):
        s=ClaudeSession(config('Claude Code'),lambda:None)
        s.stream_id=None;s.stream_blocks={};s.finalized_blocks=set()
        block={'type':'image','source':{'type':'base64','media_type':'image/png','data':'AA=='}}
        s.stream({'type':'message_start','message':{'id':'stream'}})
        s.stream({'type':'content_block_start','index':0,'content_block':block})
        self.assertIn('stream:0',s.rows)
        s.event({'type':'assistant','uuid':'native','message':{'id':'stream','role':'assistant','content':[block]}})
        self.assertNotIn('stream:0',s.rows);self.assertTrue(s.rows['native:0']['media'])

    def test_same_native_tool_id_metadata_preserves_media_but_explicit_result_clears(self):
        pi=PiSession(config('pi'),lambda:None)
        pi.project({'role':'toolResult','toolCallId':'same','content':[IMAGE]},'result')
        original=pi.rows['tool-same']['media']
        pi.project({'id':'assistant','role':'assistant','content':[{'type':'toolCall','id':'same','name':'image','arguments':{}}]},'assistant')
        self.assertEqual(pi.rows['tool-same']['media'],original)
        self.assertEqual(pi.rows['tool-same']['status'],'completed')
        pi.project({'role':'toolResult','toolCallId':'same','content':[]},'result')
        self.assertEqual(pi.rows['tool-same']['media'],[])
        claude=ClaudeSession(config('Claude Code'),lambda:None)
        claude.project({'type':'user','message':{'role':'user','content':[{'type':'tool_result','tool_use_id':'same','content':[IMAGE]}]}})
        original=claude.rows['tool-same']['media']
        block={'type':'tool_use','id':'same','name':'image','input':{}}
        claude.project({'type':'assistant','uuid':'assistant','message':{'role':'assistant','content':[block]}})
        self.assertEqual(claude.rows['tool-same']['media'],original)
        claude.stream_id='stream';claude.stream_blocks={0:block};claude.finalized_blocks=set()
        claude.stream_row(0)
        self.assertEqual(claude.rows['tool-same']['media'],original)
        claude.project({'type':'user','message':{'role':'user','content':[{'type':'tool_result','tool_use_id':'same','content':[]}]}})
        self.assertEqual(claude.rows['tool-same']['media'],[])

    def test_private_materialization_size_bound_and_same_block_reuse(self):
        with tempfile.TemporaryDirectory() as temporary:
            s=Session(config(ownerDirectory=str(pathlib.Path(temporary)/"session.native")),lambda:None)
            first=s.media(IMAGE)[0];second=s.media(IMAGE)[0]
            self.assertEqual(first,second)
            other=Session(config(ownerDirectory=str(pathlib.Path(temporary)/"other.native")),lambda:None).media({'type':'image','mimeType':'image/png','data':'AA=='})[0]
            self.assertEqual(pathlib.Path(first['source']).parent,pathlib.Path(other['source']).parent)
            self.assertEqual((pathlib.Path(temporary)/'media-cache.lock').stat().st_mode & 0o777,0o600)
            self.assertEqual(pathlib.Path(first['source']).read_bytes(),b'png fixture')
            self.assertEqual(first['sizeBytes'],11)
            self.assertEqual(pathlib.Path(first['source']).stat().st_mode & 0o777,0o600)
            bad=s.media({'type':'image','mimeType':'image/png','data':'bad!@'})[0]
            self.assertTrue(bad['source'].startswith('luma-error:'))
            self.assertEqual(len(list((pathlib.Path(temporary)/'media-cache').iterdir())),2)

    def test_imports_are_linked_by_submission_id_not_equal_text(self):
        s=Session(config(deliveries={'sent':{'state':'accepted','payload':{'images':['/private/import.png']}}}),lambda:None)
        s.row({'id':'sent','role':'user','text':''});s.row({'id':'other','role':'user','text':''})
        self.assertEqual(s.rows['sent']['media'][0]['source'],'/private/import.png')
        self.assertNotIn('media',s.rows['other'])

if __name__=='__main__': unittest.main()
