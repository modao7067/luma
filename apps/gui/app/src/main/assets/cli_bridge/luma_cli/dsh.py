import json
import pathlib
import re
import shutil
import tempfile
import uuid
from .acp import AcpSession
from .balances import account_rows, official_result, official_rows
from .base import item, text
from .transport import Rejected

class DshSession(AcpSession):
    balance_rows = ()
    cache_usage = None
    last_usage = None
    commands = ()

    def apply_usage(self, update):
        self.last_usage = update
        if self.cache_usage:
            update = {**update, **{k: v for k, v in self.cache_usage.items() if v is not None}}
        super().apply_usage(update)

    def _set_balance_rows(self, rows):
        with self.lock:
            self.balance_rows = list(rows)
            self.seq += 1

    def refresh_balances(self):
        try:
            account = self.call('luma/balance/read', {'sessionId': self.data['nativeId']}, timeout=8)
        except Exception:
            account = None
        if isinstance(account, dict) and account.get('status') == 'ready' and account.get('wallets'):
            self._set_balance_rows(account_rows(account))
            return
        try:
            official = official_result()
            wallets = official.get('wallets') if official.get('status') == 'ready' else None
            self._set_balance_rows(official_rows(official) if wallets else [{'label': '余额', 'value': '不可用'}])
        except Exception:
            self._set_balance_rows([{'label': '余额', 'value': '不可用'}])

    def can_steer(self, payload):
        return (
            bool(self.data['capabilities'].get('steer'))
            and not payload.get('images')
            and not payload.get('text', '').lstrip().startswith('/')
        )

    def snapshot(self, since=None):
        value = super().snapshot(since)
        if not value.get('unchanged'):
            if self.balance_rows:
                value['metrics'] = [*value.get('metrics', []), *self.balance_rows]
            if self.data.get('running') and self.data['capabilities'].get('steer'):
                deliveries = self.config.get('deliveries', {})
                for entry in value.get('hostQueue', []):
                    delivery = deliveries.get(entry.get('id'), {})
                    if self.can_steer(delivery.get('payload', {})):
                        entry['canSteer'] = True
        return value

    def release_mode(self):
        if not getattr(self, 'history_protocol', False) or not self.data.get('historyReady'): return None
        return 'process' if any(r.get('role') == 'user' and r.get('origin') != 'local' for r in self.rows.values()) else None

    supported_acp_version = re.compile(r'^0\.2\.\d+(?:-[0-9A-Za-z.-]+)?$')

    def start(self, argv):
        self.permission_protocol = False
        self.permission_revision = 0
        self.permission_unavailable = '当前 DSH 版本未提供已验证的权限扩展；保留官方 ACP。'
        self.permission_patch = None
        self.permission_module = None
        self.commands = set()
        self.stream_attempt = None
        self.live_streamed = set()
        self.native_queue = []
        self.question_requests = {}
        executable = shutil.which(argv[0])
        manifest = pathlib.Path(executable).resolve().parent.parent / 'package.json' if executable else None
        try:
            package = json.loads(manifest.read_text())
        except (OSError, ValueError, AttributeError):
            package = {}
        if package.get('name') == '@deepseek-ai/dsh' and self.supported_acp_version.fullmatch(str(package.get('version', ''))):
            source = pathlib.Path(__file__).with_name('dsh_acp.mjs').absolute()
            with tempfile.NamedTemporaryFile('w', prefix='luma-dsh-acp-', suffix='.mjs', dir='/tmp', delete=False) as f:
                f.write(source.read_text()); self.permission_module = pathlib.Path(f.name)
            module = self.permission_module
            patch = [
                {'id': 'acp', 'name': '@deepseek-ai/dsh-acp', 'disabled': True},
                {'insert': [
                    {'id': 'luma-auto-review', 'name': '@deepseek-ai/dsh-experimental-auto-review'},
                    {'id': 'luma-tool-ask-user', 'name': '@deepseek-ai/dsh-tool-ask-user'},
                    {'id': 'luma-time-context', 'name': '@deepseek-ai/dsh-time-context'},
                    {'id': 'luma-tool-present', 'name': '@deepseek-ai/dsh-tool-present'},
                    {'id': 'luma-dsh-acp', 'name': str(module), 'inject': ['acpAppStartup']},
                ]},
            ]
            with tempfile.NamedTemporaryFile('w', prefix='luma-dsh-acp-', suffix='.json', dir='/tmp', delete=False) as f:
                json.dump(patch, f); self.permission_patch = pathlib.Path(f.name)
            argv = [*argv, '--patch', str(self.permission_patch)]
        try:
            super().start(argv)
        except Exception:
            self.remove_patch(); raise

    def remove_patch(self):
        path = getattr(self, 'permission_patch', None)
        if path: path.unlink(missing_ok=True)
        self.permission_patch = None
        module = getattr(self, 'permission_module', None)
        if module: module.unlink(missing_ok=True)
        self.permission_module = None

    def exited(self, error):
        self.remove_patch()
        super().exited(error)

    def connect(self):
        restoring = bool(self.config.get('nativeId'))
        self.update(historyReady=False)
        super().connect()
        self.update(historyReady=False)
        meta = self.initialize_info.get('_meta', {})
        history = meta.get('lumaHistory', {})
        self.history_protocol = history.get('protocol') == 1 and history.get('available') is True
        if self.history_protocol and restoring:
            try:
                self.restore_history()
            except Exception as exc:
                self.update(error='DSH 原生历史读取失败：' + str(exc))
        elif restoring:
            self.update(error='当前 DSH 原生历史读取能力不可用；已有会话引用保持不变。')
        else:
            self.update(historyReady=True)
        capability = meta.get('lumaPermissions', {})
        self.permission_protocol = capability.get('protocol') == 1 and capability.get('available') is True
        available = self.permission_protocol
        if self.permission_protocol:
            try: self.refresh_permissions()
            except Exception as exc:
                self.permission_unavailable = str(exc); available = False; self.update(permission='', permissions=[])
        else:
            self.permission_unavailable = capability.get('reason', self.permission_unavailable)
        self._sync_extensions(meta)
        self.update(notice=self.data['notice'] + (' 权限选项由同一 DSH 进程的原生权限预设服务提供，仅明确选择后改变本会话；无文件沙箱或不逐次审批会降低对应保护，以每项实际组合为准。' if available else
                    ' DSH 官方 ACP 不提供会话模式切换；权限扩展不可用：' + self.permission_unavailable))
        self.refresh_balances()

    def _sync_extensions(self, meta):
        if not isinstance(meta, dict): return
        commands_cap = meta.get('lumaCommands', {})
        if isinstance(commands_cap, dict) and commands_cap.get('available'):
            try:
                res = self.call('luma/commands/list', {'sessionId': self.data['nativeId']}, timeout=8)
                self.commands = {c['name'] for c in res.get('commands', []) if isinstance(c, dict) and c.get('name')}
            except Exception:
                self.commands = set()
        skills = []
        skills_cap = meta.get('lumaSkills', {})
        if isinstance(skills_cap, dict) and skills_cap.get('available'):
            try:
                res = self.call('luma/skills/list', {'sessionId': self.data['nativeId']}, timeout=8)
                skills = [
                    {'id': s['id'], 'name': s.get('name', s['id']), 'description': s.get('description', ''), 'path': s.get('path', '')}
                    for s in res.get('skills', []) if isinstance(s, dict) and s.get('id')
                ]
            except Exception:
                skills = []
        steer_ok = bool(isinstance(meta.get('lumaSteer'), dict) and meta['lumaSteer'].get('available'))
        rename_ok = bool(isinstance(meta.get('lumaRename'), dict) and meta['lumaRename'].get('available'))
        if steer_ok or rename_ok or skills or getattr(self, 'commands', ()):
            caps = {
                **self.data['capabilities'],
                'steer': steer_ok,
                'skills': bool(skills),
                'maxSkills': 1 if skills else 0,
                'compact': 'compact' in getattr(self, 'commands', ()),
                'rename': rename_ok,
            }
            self.update(skills=skills, capabilities=caps)

    def restore_history(self):
        messages, offset, through = [], 0, None
        while True:
            params = {'sessionId': self.data['nativeId'], 'offset': offset}
            if through is not None: params['throughSeq'] = through
            restored = self.call('luma/history/read', params)
            if restored.get('sessionId') != self.data['nativeId']: raise Rejected('DSH 历史返回了不同会话身份')
            if type(restored.get('throughSeq')) is not int or restored['throughSeq'] < -1: raise Rejected('DSH 原生历史缺少有效快照边界')
            if through is not None and restored.get('throughSeq') != through: raise Rejected('DSH 历史分页边界改变')
            through = restored.get('throughSeq')
            page = restored.get('messages')
            if not isinstance(page, list) or any(not isinstance(row, dict) or not isinstance(row.get('id'), str) for row in page):
                raise Rejected('DSH 原生历史格式不兼容')
            messages.extend(page)
            following = restored.get('nextOffset')
            if following is None: break
            if type(following) is not int or following <= offset or following != len(messages): raise Rejected('DSH 原生历史分页没有前进')
            offset = following
        if len({row['id'] for row in messages}) != len(messages): raise Rejected('DSH 原生历史身份重复')
        admissions = {native: ident for ident, native in self.config.get('submissionNativeIds', {}).items()}
        for row in messages:
            if row.get('role') == 'user' and row.get('nativeMessageId') in admissions:
                row['id'] = admissions[row['nativeMessageId']]
        with self.lock:
            if self.data['running']: raise Rejected('会话已开始运行，不能覆盖实时输出')
            for row in messages: self.row(row)
            for sub in restored.get('subagents', []) if isinstance(restored.get('subagents'), list) else []:
                if isinstance(sub, dict) and sub.get('id'):
                    self.subagent(sub['id'], **{k: v for k, v in sub.items() if k != 'id'})
            restored_usage = restored.get('usage')
            if isinstance(restored_usage, dict):
                self.cache_usage = {k: restored_usage.get(k) for k in ('input', 'cacheRead', 'cacheWrite', 'output')}
                self.apply_usage(restored_usage)
            self.update(historyReady=True, title=restored.get('title', ''), notice=self.data['notice'].replace('原生恢复不重放历史，重启托管服务后只显示本次连接输出。', '') + '历史由同一原生会话的完整日志恢复。')

    def apply_permissions(self, value):
        if not isinstance(value, dict): raise Rejected('DSH 原生权限状态不兼容')
        if value.get('sessionId') != self.data['nativeId']: raise Rejected('DSH 权限状态返回了不同会话身份')
        current = value.get('currentValue')
        options = value.get('options')
        if not isinstance(current, str) or not isinstance(options, list): raise Rejected('DSH 原生权限状态不兼容')
        rows = []
        for option in options:
            if not isinstance(option, dict) or not isinstance(option.get('value'), str) or not isinstance(option.get('name'), str): raise Rejected('DSH 原生权限选项不兼容')
            rows.append({'id': option['value'], 'label': option['name'], 'description': option.get('description', ''),
                         'sandbox': option.get('sandbox', ''), 'approval': option.get('approval', '')})
        self.permission_revision += 1
        self.update(permission=current, permissions=rows)

    def refresh_permissions(self):
        if not self.permission_protocol: raise Rejected('当前 DSH ACP 未提供可用的会话权限切换')
        self.apply_permissions(self.call('luma/permissions/read', {'sessionId': self.data['nativeId']}))

    def _append_stream_delta(self, kind, delta_text):
        if not delta_text: return
        role = 'reasoning' if kind == 'agent_thought_chunk' else 'assistant'
        row_kind = 'reasoning' if role == 'reasoning' else ''
        if self.chunk_kind != kind:
            self.finish_chunk()
            self.chunk_kind = kind
            self.chunk_id = str(uuid.uuid4())
        old = self.rows.get(self.chunk_id, item(self.chunk_id, role, '', kind=row_kind))
        self.row({**old, 'text': old.get('text', '') + delta_text, 'status': 'running'})

    def _handle_stream_frame(self, value):
        frame = value.get('frame')
        if not isinstance(frame, dict): return
        ftype = frame.get('type')
        attempt_id = frame.get('attemptId', '')
        if ftype == 'start':
            self.finish_chunk()
            self.stream_attempt = attempt_id
            self.live_streamed = set()
        elif ftype == 'chunk':
            chunk = frame.get('chunk')
            if not isinstance(chunk, dict): return
            ctype = chunk.get('type')
            if ctype == 'text-delta' and chunk.get('text'):
                self.live_streamed.add('agent_message_chunk')
                self._append_stream_delta('agent_message_chunk', chunk['text'])
            elif ctype == 'reasoning-delta' and chunk.get('text'):
                self.live_streamed.add('agent_thought_chunk')
                self._append_stream_delta('agent_thought_chunk', chunk['text'])
        elif ftype == 'end':
            self.finish_chunk()

    def _handle_question_request(self, value):
        req_id = str(value.get('requestId', ''))
        if not req_id: return
        if not hasattr(self, 'question_requests'): self.question_requests = {}
        self.question_requests[req_id] = value
        intent = value.get('intent') or {}
        raw_questions = value.get('questions') or []
        if isinstance(intent, dict) and intent.get('kind') == 'plan-review':
            plan_q = raw_questions[0] if raw_questions and isinstance(raw_questions[0], dict) else {}
            body = plan_q.get('question', '请审阅当前计划')
            questions = [{
                'id': '0',
                'header': plan_q.get('header', 'Plan'),
                'prompt': body,
                'options': ['批准计划 (Approve)', '拒绝/取消'],
                'multiple': False,
            }]
            self.request(
                req_id, value, '计划审阅 (Plan Review)', body,
                [{'id': 'allow', 'label': '提交'}, {'id': 'deny', 'label': '取消'}],
                questions, submit_choice='allow', skip_choice='deny',
            )
            return
        questions = []
        for idx, q in enumerate(raw_questions):
            if not isinstance(q, dict): continue
            opts = [o.get('label', '') for o in (q.get('options') or []) if isinstance(o, dict) and o.get('label')]
            questions.append({
                'id': str(idx),
                'header': q.get('header', f'Q{idx + 1}'),
                'prompt': q.get('question', ''),
                'options': opts,
                'multiple': bool(q.get('multiSelect', False)),
            })
        self.request(
            req_id, value, value.get('title') or '问题确认', '',
            [{'id': 'allow', 'label': '提交'}, {'id': 'deny', 'label': '跳过'}],
            questions, submit_choice='allow', skip_choice='deny',
        )

    def answer(self, p):
        req_id = str(p.get('id', ''))
        qreq = getattr(self, 'question_requests', {}).get(req_id)
        if not qreq:
            return super().answer(p)
        choice = p.get('choice', '')
        intent = qreq.get('intent') or {}
        raw_questions = qreq.get('questions') or []
        if choice == 'deny':
            self.call('luma/questions/answer', {
                'sessionId': self.data['nativeId'],
                'requestId': req_id,
                'cancelled': True,
            })
            self.question_requests.pop(req_id, None)
            self.answered(req_id)
            return {}
        if choice != 'allow':
            raise Rejected('无效审批选项')
        if isinstance(intent, dict) and intent.get('kind') == 'plan-review':
            plan_q = raw_questions[0] if raw_questions and isinstance(raw_questions[0], dict) else {}
            q_text = plan_q.get('question', '')
            vals = (p.get('answers') or {}).get('0') or []
            val = vals[0] if vals else 'Approve'
            if val in ('批准计划 (Approve)', 'Approve'):
                ans = {q_text: intent.get('approve', 'Approve')}
                self.call('luma/questions/answer', {
                    'sessionId': self.data['nativeId'],
                    'requestId': req_id,
                    'answers': ans,
                })
            elif val == '拒绝/取消':
                self.call('luma/questions/answer', {
                    'sessionId': self.data['nativeId'],
                    'requestId': req_id,
                    'cancelled': True,
                })
            else:
                ans = {q_text: val}
                self.call('luma/questions/answer', {
                    'sessionId': self.data['nativeId'],
                    'requestId': req_id,
                    'answers': ans,
                })
            self.question_requests.pop(req_id, None)
            self.answered(req_id)
            return {}
        answers = {}
        for idx, q in enumerate(raw_questions):
            if not isinstance(q, dict): continue
            q_text = q.get('question', '')
            vals = (p.get('answers') or {}).get(str(idx)) or []
            if not vals:
                raise Rejected('请回答：' + q_text)
            if not q.get('multiSelect') and len(vals) != 1:
                raise Rejected('此问题只允许一个回答')
            answers[q_text] = ', '.join(vals)
        self.call('luma/questions/answer', {
            'sessionId': self.data['nativeId'],
            'requestId': req_id,
            'answers': answers,
        })
        self.question_requests.pop(req_id, None)
        self.answered(req_id)
        return {}

    def event(self, frame):
        method = frame.get('method')
        if method == 'luma/usage/cache':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                self.cache_usage = {key: value.get(key) for key in ('input', 'cacheRead', 'cacheWrite', 'output')}
                self.apply_usage(self.last_usage or self.cache_usage)
            return
        if method == 'luma/submissions/accepted':
            value = frame.get('params', {})
            ident = value.get('submissionId')
            if (self.initialize_info.get('_meta', {}).get('lumaSubmissions', {}).get('protocol') == 1
                    and value.get('sessionId') == self.data['nativeId'] and ident == self.current_submission
                    and isinstance(value.get('nativeMessageId'), str) and value['nativeMessageId']):
                self.config.setdefault('submissionNativeIds', {})[ident] = value['nativeMessageId']
                self.receipt(ident, 'accepted')
            return
        if method == 'luma/permissions/updated':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                try: self.apply_permissions(value)
                except (Rejected, ValueError, TypeError) as exc:
                    self.update(permission='', permissions=[], error='DSH 权限状态不可用：' + str(exc))
            return
        if method == 'luma/stream/frame':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                self._handle_stream_frame(value)
            return
        if method == 'luma/questions/request':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                self._handle_question_request(value)
            return
        if method == 'luma/questions/cancelled':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                req_id = str(value.get('requestId', ''))
                if hasattr(self, 'question_requests'):
                    self.question_requests.pop(req_id, None)
                self.answered(req_id)
            return
        if method == 'luma/command/done':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                raw_sub = value.get('submissionId')
                sub_id = raw_sub if isinstance(raw_sub, str) and raw_sub else ''
                if sub_id and sub_id in self.config.get('receipts', {}):
                    self.receipt(sub_id, 'accepted')
                    self.consumed(sub_id)
                msg = value.get('message') or value.get('text') or ''
                if isinstance(msg, str) and msg.strip():
                    row_id = str(uuid.uuid4())
                    self.row(item(row_id, 'assistant', msg.strip(), status='completed'))
                if sub_id and sub_id == self.current_submission:
                    self.finish_chunk()
                    self.current_submission = None
                    self.update(running=False)
            return
        if method == 'luma/inbox/updated':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                items = value.get('items') if isinstance(value.get('items'), list) else value.get('queue', [])
                self.native_queue = [
                    {
                        'id': str(it['id']),
                        'text': it.get('text', ''),
                        'canRemove': bool(it.get('canRemove', True)),
                        'canSteer': bool(it.get('canSteer', True)),
                        'canEdit': bool(it.get('canEdit', True)),
                        'hostOwned': False,
                    }
                    for it in items if isinstance(it, dict) and it.get('id')
                ]
                self.update(queue=list(self.native_queue))
            return
        if method == 'luma/subagents/updated':
            value = frame.get('params', {})
            if value.get('sessionId') == self.data['nativeId']:
                subs = value.get('subagents') if isinstance(value.get('subagents'), list) else []
                if isinstance(value.get('subagent'), dict):
                    subs = [*subs, value['subagent']]
                for sub in subs:
                    if isinstance(sub, dict) and sub.get('id'):
                        self.subagent(sub['id'], **{k: v for k, v in sub.items() if k != 'id'})
            return
        if method == 'session/update':
            params = frame.get('params', {})
            update = params.get('update', {})
            kind = update.get('sessionUpdate')
            if kind in ('agent_message_chunk', 'agent_thought_chunk') and kind in getattr(self, 'live_streamed', set()):
                return
            if kind in ('tool_call', 'tool_call_update') and getattr(self, 'live_streamed', None):
                self.live_streamed.clear()
            if kind == 'user_message_chunk' and getattr(self, 'steered_echo_queue', None) and self.chunk_kind != 'user_message_chunk':
                steer_id, steer_body = self.steered_echo_queue[0]
                chunk_text = text(update.get('content'))
                if not steer_body or not chunk_text or steer_body.startswith(chunk_text) or chunk_text.startswith(steer_body):
                    self.steered_echo_queue.pop(0)
                    self.finish_chunk()
                    self.chunk_id = steer_id
                    self.chunk_kind = 'user_message_chunk'
                    self.row(item(steer_id, 'user', '', origin='native'))
        super().event(frame)

    def queue_change(self, data):
        action = data.get('action')
        ident = data.get('id')
        if any(q.get('id') == ident for q in getattr(self, 'native_queue', [])):
            res = self.call('luma/inbox/mutate', {
                'sessionId': self.data['nativeId'],
                'action': action,
                'id': ident,
                'text': data.get('text', ''),
            })
            items = res.get('items', [])
            self.native_queue = [
                {
                    'id': str(it['id']),
                    'text': it.get('text', ''),
                    'canRemove': bool(it.get('canRemove', True)),
                    'canSteer': bool(it.get('canSteer', True)),
                    'canEdit': bool(it.get('canEdit', True)),
                    'hostOwned': False,
                }
                for it in items if isinstance(it, dict) and it.get('id')
            ]
            self.update(queue=list(self.native_queue))
            return {}
        if action == 'steer':
            with self.lock:
                delivery = self.config.get('deliveries', {}).get(ident)
                if not delivery or delivery.get('state') != 'queued':
                    raise Rejected('消息已离开待发队列')
                if not self.data.get('running') or not self.can_steer(delivery.get('payload', {})):
                    raise Rejected('当前消息不支持运行中引导')
                delivery['payload']['steer'] = True
                self.seq += 1
                self.delivery_wake.set()
            return {}
        return super().queue_change(data)

    def validate_prompt(self, payload):
        super().validate_prompt(payload)
        skills = payload.get('skills') or []
        if len(skills) > 1:
            raise Rejected('DeepSeek Harness 每条消息最多选择一个 Skill')
        if any(s not in {k['id'] for k in self.data.get('skills', [])} for s in skills):
            raise Rejected('Skill 不在原生目录中')
        raw_text = payload.get('text', '').lstrip()
        if not skills and raw_text:
            head = raw_text.split(maxsplit=1)[0]
            if head in ('/clear', '/reset', '/new'):
                raise Rejected('此原生命令会更换会话 ID；请使用 GUI 新建会话，保留当前会话的身份与历史')

    def prompt(self, p):
        ident = p['submissionId']
        body = p.get('text', '')
        skills = p.get('skills') or []
        if skills:
            body = f'/{skills[0]} {body}'.strip()
        if p.get('steer') and self.data.get('running'):
            res = self.call('luma/prompt/steer', {
                'sessionId': self.data['nativeId'],
                'submissionId': ident,
                'text': body,
            })
            if not res.get('accepted'):
                raise Rejected(res.get('reason') or '当前回合无法引导')
            with self.lock:
                self.finish_chunk()
                self.chunk_id = self.chunk_kind = None
                self.row(item(ident, 'user', body, origin='native', status='completed'))
                if not hasattr(self, 'steered_echo_queue'):
                    self.steered_echo_queue = []
                self.steered_echo_queue.append((ident, body))
                self.consumed(ident)
            return True
        stripped = body.lstrip()
        if stripped.startswith('/') and not p.get('images') and getattr(self, 'commands', None):
            cmd_token = stripped[1:].split(maxsplit=1)[0] if len(stripped) > 1 else ''
            if cmd_token in self.commands:
                self.current_submission = ident
                self.update(running=True, error=None)
                self.row(item(ident, 'user', stripped, origin='native', status='completed'))
                res = self.call('luma/commands/execute', {
                    'sessionId': self.data['nativeId'],
                    'submissionId': ident,
                    'text': stripped,
                })
                if res.get('handled') is True:
                    snap = res.get('snapshot')
                    if isinstance(snap, dict):
                        try: self.apply_permissions(snap)
                        except Exception: pass
                    out_text = res.get('text', '')
                    if isinstance(out_text, str) and out_text.strip():
                        self.row(item(f'{ident}:cmd', 'assistant', out_text.strip(), status='completed'))
                    self.consumed(ident)
                    if not res.get('running'):
                        self.current_submission = None
                        self.update(running=False)
                    return True
        if skills:
            p = {**p, 'text': body}
        self.live_streamed = set()
        self.steered_echo_queue = []
        return super().prompt(p)

    def compact(self):
        if 'compact' not in getattr(self, 'commands', set()):
            return super().compact()
        return self.submit({'submissionId': str(uuid.uuid4()), 'text': '/compact'})

    def rename(self, title):
        if not self.data['capabilities'].get('rename'):
            return super().rename(title)
        title = (title or '').strip()
        if not title:
            raise Rejected('会话标题不能为空')
        res = self.call('luma/session/rename', {'sessionId': self.data['nativeId'], 'title': title})
        self.update(title=res.get('title', title))
        return {}

    def configure(self, values):
        if 'permission' not in values: return super().configure(values)
        if self.data['running']: raise Rejected('请等待回合结束再更改权限')
        if not self.permission_protocol: raise Rejected('当前 DSH ACP 未提供可用的会话权限切换')
        selected = values['permission']
        if selected not in {p['id'] for p in self.data['permissions']}: raise Rejected('权限预设不在 DSH 原生目录中')
        other = {k: v for k, v in values.items() if k != 'permission'}
        if other: super().configure(other)
        revision = self.permission_revision
        try:
            actual = self.call('luma/permissions/set', {'sessionId': self.data['nativeId'], 'value': selected})
            self.apply_permissions(actual)
            if self.data['permission'] != selected: raise Rejected('DSH 尚未确认所选权限')
        except Rejected:
            try: self.refresh_permissions()
            except Exception: self.update(permission='', permissions=[])
            raise
        except Exception:
            if self.permission_revision == revision: self.update(permission='')
            raise
        return {}

    def refresh(self):
        if self.permission_protocol: self.refresh_permissions()
        self.refresh_balances()
        return {}
