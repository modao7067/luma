import assert from 'node:assert/strict';
import { execFileSync } from 'node:child_process';
import { realpathSync } from 'node:fs';
import { dirname, join } from 'node:path';
import { pathToFileURL } from 'node:url';
import { projectHistory, toolKindFor, toolUpdate } from '../../../apps/gui/app/src/main/assets/cli_bridge/luma_cli/dsh_acp.mjs';

const dsh = process.env.LUMA_DSH_PACKAGE ?? dirname(dirname(realpathSync(
  execFileSync('which', ['dsh'], { encoding: 'utf8' }).trim())));
const api = await import(pathToFileURL(join(dsh, 'node_modules/@deepseek-ai/dsh-session/lib/index.js')).href);

const user = (seq, id, text, surfaceOp = 'append') => ({
  type: 'user/message', seq, time: seq, surfaceOp,
  data: { id, role: 'user', source: { kind: 'user' }, content: [{ type: 'text', text }] },
});
const assistant = (seq, id, content, extra = {}) => ({
  type: 'assistant/message', seq, time: seq, surfaceOp: 'append',
  data: { turn: 1, step: 1, message: { id, role: 'assistant', content,
    source: { kind: 'model', provider: 'fixture', model: 'fixture' } }, stream: [], ...extra },
});
const events = [
  { type: 'turn/start', seq: 0, time: 0, data: { turn: 1 } },
  user(1, 'same-a', 'repeated'),
  user(2, 'same-b', 'repeated'),
  assistant(3, 'assistant', [
    { type: 'reasoning', text: 'native reasoning' },
    { type: 'text', text: 'native body' },
    { type: 'tool-call', id: 'tool-a', name: 'read', arguments: '{}' },
  ], { interrupted: true }),
  { type: 'tool/call', seq: 4, time: 4,
    data: { turn: 1, step: 1, callId: 'tool-a', name: 'read', arguments: '{}' } },
  { type: 'tool/result', seq: 5, time: 5, surfaceOp: 'append', data: {
    turn: 1, step: 1, error: { code: api.TOOL_OUTCOME_UNKNOWN, name: 'ToolOutcomeUnknownError' },
    message: { id: 'result-a', role: 'tool', toolCallId: 'tool-a', isError: true,
      source: { kind: 'tool', callId: 'tool-a' },
      content: [{ type: 'text', text: 'original native recovery' }] },
  } },
  user(6, 'summary', 'model-only compaction copy', { op: 'replace', startSeq: 1, endSeq: 2 }),
  { type: 'session/title', seq: 7, time: 7, data: { title: 'native title' } },
  { type: 'plan/mode', seq: 8, time: 8, data: { enabled: true } },
  { type: 'goal/change', seq: 9, time: 9, data: { active: true, goal: 'ship feature' } },
  { type: 'subagent/start', seq: 10, time: 10, data: { sessionId: 'child-1', agentName: 'explore', callId: 'tool-a', task: 'inspect repo', turn: 1 } },
  { type: 'subagent/end', seq: 11, time: 11, data: { sessionId: 'child-1', agentName: 'explore', callId: 'tool-a', status: 'done', durationMs: 1200 } },
];

const projected = projectHistory(events, api);
const users = projected.messages.filter(row => row.role === 'user');
assert.equal(users.length, 2);
assert.deepEqual(users.map(row => row.text), ['repeated', 'repeated']);
assert(!projected.messages.some(row => row.text.includes('model-only')));
assert.equal(projected.messages.find(row => row.kind === 'reasoning').status, 'cancelled');
const tool = projected.messages.find(row => row.id === 'tool-tool-a');
assert.equal(tool.status, 'unknown');
assert.equal(tool.toolKind, 'read');
assert.equal(tool.outcome, 'original native recovery');
assert.equal(projected.title, 'native title');
assert.equal(projected.throughSeq, 11);
assert.equal(projected.planMode, true);
assert.deepEqual(projected.goal, { active: true, goal: 'ship feature' });
assert.equal(projected.subagents.length, 1);
assert.equal(projected.subagents[0].id, 'child-1');
assert.equal(projected.subagents[0].status, 'succeeded');
assert.equal(users[0].turnId, '1');
assert.deepEqual(projectHistory(events, api), projected);
assert.equal(toolKindFor('bash'), 'execute');
assert.equal(toolKindFor('read'), 'read');
assert.equal(toolKindFor('grep'), 'search');
assert.equal(toolKindFor('web_fetch'), 'fetch');
assert.equal(toolKindFor('unknown_tool'), 'other');
const live = toolUpdate({ method: 'session/update', params: { sessionId: 's', update: {
  sessionUpdate: 'tool_call', toolCallId: 't', title: 'read', kind: 'other', rawInput: { path: '/tmp/x' } } } });
assert.equal(live.params.update.kind, 'read');
assert.deepEqual(live.params.update.locations, [{ path: '/tmp/x' }]);
const updateOnly = { method: 'session/update', params: { sessionId: 's', update: {
  sessionUpdate: 'tool_call_update', toolCallId: 't', status: 'completed' } } };
assert.equal(toolUpdate(updateOnly), updateOnly);
console.log('PASS constructed native-type projection events with installed official helpers: repeated text keeps native IDs; compaction retains human history; interrupted prefix; unknown tool result; native title and stable provenance.');
