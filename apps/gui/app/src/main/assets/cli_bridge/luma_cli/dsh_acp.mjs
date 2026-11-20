import { randomUUID } from 'node:crypto';
import { Readable, Writable } from 'node:stream';

export const name = 'luma-dsh-acp';
export const inject = ['loader', 'agents', 'llm', 'sessionPersistence', 'sessions'];

const PREFIX = 'luma/permissions/';
const HISTORY = 'luma/history/read';
const BALANCE = 'luma/balance/read';
const isZh = () => String(process.env.LUMA_LANG ?? 'zh').trim().toLowerCase().startsWith('zh');
const tr = (zh, en) => (isZh() ? zh : en);

const TOOL_KINDS = {
  bash: 'execute', pwsh: 'execute', run_code: 'execute', terminal_open: 'execute', terminal_send: 'execute',
  terminal_signal: 'execute', terminal_close: 'execute',
  read: 'read', read_image: 'read', list_subagent_models: 'read', terminal_list: 'read', terminal_read: 'read',
  write: 'edit', edit: 'edit', str_replace_editor: 'edit',
  glob: 'search', grep: 'search', session_search: 'search', session_event_search: 'search',
  session_event_trace: 'search', session_trace: 'search',
  web_fetch: 'fetch', web_search: 'fetch', session_event_read: 'read',
};

const PRESET_CATALOG = Object.freeze([
  {
    id: 'standard',
    name: 'Standard',
    description: 'Default DeepSeek Harness agent preset with native tool calling.',
  },
  {
    id: 'ptc',
    name: 'PTC (Programmatic Tool Calling)',
    description: 'Execute TypeScript/Python programs via run_code against the SDK toolset.',
  },
  {
    id: 'minimal',
    name: 'Minimal',
    description: 'Minimal tool surface focused on shell execution and image reading.',
  },
  {
    id: 'cordis',
    name: 'Cordis',
    description: 'Standard agent preset plus Cordis runtime inspection tools.',
  },
]);

export function toolKindFor(name) { return TOOL_KINDS[String(name ?? '')] ?? 'other'; }

export function toolUpdate(message) {
  const update = message?.method === 'session/update' ? message.params?.update : undefined;
  if (!update || (update.sessionUpdate !== 'tool_call' && update.sessionUpdate !== 'tool_call_update')) return message;
  if (typeof update.title !== 'string' || !update.title) return message;
  const kind = toolKindFor(update.title);
  if (kind === 'other' && update.kind !== 'other') return message;
  const next = { ...update, kind };
  const raw = update.rawInput;
  if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
    const path = raw.file_path ?? raw.path ?? raw.filePath;
    if (typeof path === 'string' && (kind === 'read' || kind === 'edit')) {
      next.locations = [{ path }];
      if (raw.path === undefined) next.rawInput = { ...raw, path };
    }
    if (kind === 'edit' && typeof raw.old_string === 'string') {
      next.rawInput = { ...next.rawInput, oldString: raw.old_string, newString: raw.new_string };
    }
  }
  return { ...message, params: { ...message.params, update: next } };
}

function extractSessionPreset(events) {
  if (!Array.isArray(events)) return 'standard';
  for (let i = events.length - 1; i >= 0; i -= 1) {
    const event = events[i];
    if (event?.type === 'agent/preset' && typeof event.data?.id === 'string' && event.data.id.trim()) {
      return event.data.id.trim();
    }
  }
  return 'standard';
}

export async function apply(ctx) {
  const native = await ctx.loader.import('@deepseek-ai/dsh-acp');
  const { ndJsonStream } = await ctx.loader.import('@agentclientprotocol/sdk');
  const { interpolate } = await ctx.loader.import('@deepseek-ai/cordis-plugin-loader');
  const { resolveConfig } = await ctx.loader.import('@deepseek-ai/cordis');
  const sessionApi = await ctx.loader.import('@deepseek-ai/dsh-session');
  let llmApi = null;
  let ptcApi = null;
  let cordisToolApi = null;
  try { llmApi = await ctx.loader.import('@deepseek-ai/dsh-llm'); } catch {}

  const original = [...ctx.loader.entries()].find(entry => entry.options.id === 'acp' && entry.options.name === '@deepseek-ai/dsh-acp');
  if (!original || typeof native.apply !== 'function') throw new Error('Luma DSH adapter requires the official ACP entry');
  const config = resolveConfig(native, interpolate(original.ctx, original.options.config ?? {}));
  const wire = ndJsonStream(Writable.toWeb(process.stdout), Readable.toWeb(process.stdin));
  const output = wire.writable.getWriter();
  const pending = new Map();
  const owned = new Map();
  const prompting = new Set();
  const admissions = new Map();
  const steerAdmissions = new Map();
  const pendingQuestions = new Map();
  const pendingApprovals = new Map();
  const approvalMetaByCallId = new Map();
  const toolCallsBySession = new Map();
  const agentPresetDisposers = new Map();
  const agentSelectedPresets = new Map();
  const trackedChildrenByParent = new Map();
  const childUpdateTimers = new Map();
  const scheduled = new Set();
  const cacheUsage = new Map();
  const activeCommandSubmissions = new Map();
  let questionSeq = 0;
  let approvalSeq = 0;

  const resolveApprovalReason = request => {
    const dr = request?.displayReason;
    if (dr && typeof dr === 'object') {
      const localized = isZh() ? (dr.zh ?? dr['zh-CN']) : dr.en;
      if (typeof localized === 'string' && localized.trim()) return localized.trim();
      if (typeof dr.en === 'string' && dr.en.trim()) return dr.en.trim();
    }
    return typeof request?.reason === 'string' ? request.reason.trim() : '';
  };

  const service = () => {
    const value = ctx.get('permissionPresets');
    return value && ['catalog', 'current', 'set'].every(key => typeof value[key] === 'function') ? value : undefined;
  };
  const capability = () => ({ protocol: 1, available: !!service(), ...(!service() ? { reason: 'DSH native permission preset service is unavailable' } : {}) });

  function requireAgent(id) {
    const agent = owned.get(id);
    if (!agent || ctx.agents.get(id) !== agent) throw new Error('Session is not owned by this ACP connection');
    return agent;
  }

  function getPlanState(agent) {
    const planMode = ctx.get('planMode');
    if (!planMode || typeof planMode.get !== 'function') return { available: false, active: false };
    try {
      const state = planMode.get(agent);
      const active = Boolean(state && (state.pending !== undefined ? state.pending : state.active));
      return { available: typeof planMode.set === 'function', active, state };
    } catch {
      return { available: false, active: false };
    }
  }

  function getGoalState(agent) {
    const goals = ctx.get('goals');
    if (!goals || typeof goals.list !== 'function') return null;
    try {
      const list = goals.list(agent.session.id) ?? [];
      const active = list.find(g => g.status === 'active' || g.status === 'paused') ?? list.at(-1) ?? null;
      return active ? { id: active.id, objective: active.objective, status: active.status } : null;
    } catch {
      return null;
    }
  }

  function getAgentPreset(agent) {
    const id = agent?.session?.id;
    if (id && agentSelectedPresets.has(id)) return agentSelectedPresets.get(id);
    try {
      const events = agent?.session?.snapshotEvents?.() ?? [];
      const fromLog = extractSessionPreset(events);
      if (id) agentSelectedPresets.set(id, fromLog);
      return fromLog;
    } catch {
      return 'standard';
    }
  }

  function canChangePreset(agent) {
    try {
      const events = agent?.session?.snapshotEvents?.() ?? [];
      return !events.some(e => e?.type === 'turn/start' || e?.type === 'request/header');
    } catch {
      return false;
    }
  }

  async function applyAgentPreset(agent, presetId, recordEvent = true) {
    const valid = PRESET_CATALOG.find(item => item.id === presetId);
    if (!valid) throw new Error(`Unknown agent preset "${presetId}". Available presets: ${PRESET_CATALOG.map(p => p.id).join(', ')}`);
    const sessionId = agent.session.id;
    const current = getAgentPreset(agent);
    if (current !== presetId && !canChangePreset(agent)) {
      throw new Error('Agent preset can only be switched before the first turn starts');
    }
    const prevDispose = agentPresetDisposers.get(sessionId);
    if (typeof prevDispose === 'function') {
      try { prevDispose(); } catch {}
      agentPresetDisposers.delete(sessionId);
    }
    const disposers = [];
    if (presetId === 'ptc') {
      ptcApi ??= await ctx.loader.import('@deepseek-ai/dsh-tool-executor-ptc');
      if (ptcApi?.PtcExecutor && agent.ctx?.tools?.setExecutor) {
        const dispose = agent.ctx.tools.setExecutor(new ptcApi.PtcExecutor(agent.ctx));
        if (typeof dispose === 'function') disposers.push(dispose);
      }
    } else if (presetId === 'minimal') {
      if (agent.ctx?.tools?.restrict) {
        const view = agent.ctx.tools.view?.(agent);
        const restrictable = view?.restrictableNames ? [...view.restrictableNames] : [];
        const allowed = ['bash', 'pwsh', 'read_image', 'ask_user_question'].filter(n => restrictable.includes(n));
        if (allowed.length > 0) {
          const dispose = agent.ctx.tools.restrict({ allow: allowed });
          if (typeof dispose === 'function') disposers.push(dispose);
        }
      }
    } else if (presetId === 'cordis') {
      try {
        cordisToolApi ??= await ctx.loader.import('@deepseek-ai/dsh-tool-cordis');
        if (typeof cordisToolApi?.apply === 'function' && agent.ctx) {
          const effect = agent.ctx.plugin?.(cordisToolApi);
          if (typeof effect?.dispose === 'function') disposers.push(() => effect.dispose());
        }
      } catch {}
    }
    if (disposers.length > 0) {
      agentPresetDisposers.set(sessionId, () => {
        for (const d of disposers.splice(0)) {
          try { d(); } catch {}
        }
      });
    }
    agentSelectedPresets.set(sessionId, presetId);
    if (recordEvent && current !== presetId) {
      try { agent.session.append('agent/preset', { id: presetId }); } catch {}
    }
    return {
      sessionId,
      currentPreset: presetId,
      changeable: canChangePreset(agent),
      presets: PRESET_CATALOG,
    };
  }

  function snapshot(id) {
    const agent = requireAgent(id); const permissions = service();
    if (!permissions) throw new Error('DSH native permission preset service is unavailable');
    const catalog = permissions.catalog();
    if (!Array.isArray(catalog.options)) throw new Error('DSH permission catalog is incompatible');
    const options = catalog.options.map(option => {
      if (typeof option.value !== 'string' || typeof option.name !== 'string') throw new Error('DSH permission option is incompatible');
      const spec = permissions.presets?.[option.value] ?? (option.value === 'auto' ? { sandbox: 'danger-full-access', approval: 'ask' } : undefined);
      return { value: option.value, name: option.name, description: typeof option.description === 'string' ? option.description : '',
        sandbox: typeof spec?.sandbox === 'string' ? spec.sandbox : '', approval: typeof spec?.approval === 'string' ? spec.approval : '' };
    });
    const plan = getPlanState(agent);
    const currentValue = permissions.current(agent.session);
    return {
      sessionId: id,
      currentValue,
      options,
      planAvailable: Boolean(plan.available),
      planActive: Boolean(plan.active),
      preset: getAgentPreset(agent),
      goal: getGoalState(agent),
    };
  }

  function inboxSnapshot(agent) {
    const inbox = agent?.inbox;
    if (!inbox) return [];
    const textOf = blocks => Array.isArray(blocks) ? blocks.filter(b => b?.type === 'text').map(b => b.text).join('') : '';
    const items = [];
    for (const msg of inbox.nextStep ?? []) {
      items.push({ id: msg.id, target: 'next-step', text: textOf(msg.content), status: tr('引导', 'Steer'), canEdit: false });
    }
    for (const msg of inbox.nextTurn ?? []) {
      items.push({ id: msg.id, target: 'next-turn', text: textOf(msg.content), status: tr('待执行', 'Queued'), canEdit: false });
    }
    return items;
  }

  async function readSessionEvents(sessionId) {
    const live = owned.get(sessionId)?.session ?? ctx.get('sessions')?.get?.(sessionId);
    if (live && typeof live.snapshotEvents === 'function') {
      return live.snapshotEvents();
    }
    const query = ctx.get('sessionQuery');
    if (query && typeof query.readSession === 'function') {
      try {
        const snap = await query.readSession(sessionId);
        if (Array.isArray(snap?.events)) return snap.events;
      } catch {}
    }
    return [];
  }

  function mapSubagentStatus(nativeStatus) {
    switch (nativeStatus) {
      case 'running': return 'running';
      case 'completed':
      case 'succeeded': return 'succeeded';
      case 'failed':
      case 'error': return 'failed';
      case 'aborted':
      case 'canceled':
      case 'cancelled':
      case 'interrupted': return 'cancelled';
      default: return 'running';
    }
  }

  async function buildSubagentsSnapshot(parentId) {
    const parentAgent = owned.get(parentId);
    const byId = new Map();
    const tracked = trackedChildrenByParent.get(parentId);
    if (tracked) {
      for (const [cid, info] of tracked.entries()) byId.set(cid, { ...info });
    }
    const projections = ctx.get('sessionProjections');
    if (parentAgent && projections && typeof projections.read === 'function') {
      try {
        const cat = projections.read(parentAgent.session, 'subagentCatalog');
        for (const entry of cat?.entries ?? []) {
          const cid = entry.sessionId ?? entry.childSessionId;
          if (!cid) continue;
          const prev = byId.get(cid) ?? {};
          byId.set(cid, {
            ...prev,
            id: cid,
            name: entry.label || prev.name || entry.mode || 'subagent',
            agentType: entry.mode || prev.agentType || 'subagent',
            nativeStatus: entry.status || prev.nativeStatus || 'running',
          });
        }
      } catch {}
    }
    const subagentsSvc = ctx.get('subagents');
    if (subagentsSvc && typeof subagentsSvc.listChildren === 'function') {
      try {
        for (const entry of subagentsSvc.listChildren(parentId) ?? []) {
          const cid = entry.sessionId ?? entry.childSessionId;
          if (!cid) continue;
          const prev = byId.get(cid) ?? {};
          byId.set(cid, {
            ...prev,
            id: cid,
            name: entry.label || prev.name || entry.mode || entry.provider || 'subagent',
            agentType: entry.mode || entry.provider || prev.agentType || 'subagent',
            nativeStatus: entry.status || prev.nativeStatus || 'running',
          });
        }
      } catch {}
    }
    const results = [];
    for (const [cid, info] of byId.entries()) {
      const childEvents = await readSessionEvents(cid);
      const projected = projectHistory(childEvents, sessionApi);
      const messages = projected.messages;
      const firstUser = messages.find(m => m.role === 'user');
      const lastAssistant = [...messages].reverse().find(m => m.role === 'assistant');
      const nativeStatus = info.nativeStatus || 'running';
      results.push({
        id: cid,
        name: info.name || info.agentType || 'subagent',
        agentType: info.agentType || 'subagent',
        status: mapSubagentStatus(nativeStatus),
        nativeStatus,
        task: info.task || (firstUser ? firstUser.text.slice(0, 240) : ''),
        detail: info.detail || (lastAssistant ? lastAssistant.text.slice(0, 500) : ''),
        messages,
      });
    }
    return results;
  }

  function scheduleSubagentPublish(parentId, immediate = false) {
    if (!owned.has(parentId)) return;
    const prevTimer = childUpdateTimers.get(parentId);
    if (prevTimer) {
      clearTimeout(prevTimer);
      childUpdateTimers.delete(parentId);
    }
    const run = async () => {
      childUpdateTimers.delete(parentId);
      if (!owned.has(parentId)) return;
      try {
        const subagents = await buildSubagentsSnapshot(parentId);
        void output.write({
          jsonrpc: '2.0',
          method: 'luma/subagents/updated',
          params: { sessionId: parentId, subagents },
        }).catch(() => {});
      } catch {}
    };
    if (immediate) void run();
    else childUpdateTimers.set(parentId, setTimeout(run, 120));
  }

  function changed(id) {
    if (!owned.has(id) || scheduled.has(id)) return;
    scheduled.add(id);
    queueMicrotask(() => {
      scheduled.delete(id);
      try { void output.write({ jsonrpc: '2.0', method: PREFIX + 'updated', params: snapshot(id) }).catch(() => {}); }
      catch {  }
    });
  }

  const commandsSvc = ctx.get('commands');
  if (commandsSvc && typeof commandsSvc.register === 'function') {
    try {
      commandsSvc.register({
        name: 'preset',
        description: 'Inspect or switch the session agent preset (standard, ptc, minimal, cordis) before the first turn',
        input: { hint: '[standard|ptc|minimal|cordis]' },
        handler: async ({ agent, rawInput }) => {
          if (!agent) return { kind: 'error', text: 'No active agent.' };
          const target = (rawInput || '').trim().toLowerCase();
          if (!target) {
            const current = getAgentPreset(agent);
            const list = PRESET_CATALOG.map(p => `${p.id === current ? '* ' : '  '}${p.id} — ${p.description}`).join('\n');
            return { kind: 'success', text: `Current agent preset: ${current}\n${list}` };
          }
          try {
            await applyAgentPreset(agent, target, true);
            changed(agent.session.id);
            return { kind: 'success', text: `Switched agent preset to "${target}".` };
          } catch (err) {
            return { kind: 'error', text: String(err?.message ?? err) };
          }
        },
      });
    } catch {}
  }

  ctx.on('session/event', (session, event) => {
    const parentId = session?.header?.parentSession;
    if (parentId && owned.has(parentId)) {
      const immediate = ['turn/end', 'step/end', 'assistant/message', 'tool/result'].includes(event.type);
      scheduleSubagentPublish(parentId, immediate);
    }
    if (owned.get(session.id)?.session !== session) return;
    if (event.data?.usage && typeof event.data.usage === 'object') {
      const usage = event.data.usage;
      const prev = cacheUsage.get(session.id) ?? { input: 0, cacheRead: 0, cacheWrite: 0, output: 0, seenSeqs: new Set() };
      if (!Number.isFinite(event.seq) || !prev.seenSeqs.has(event.seq)) {
        if (Number.isFinite(event.seq)) prev.seenSeqs.add(event.seq);
        if (Number.isFinite(usage.inputTokens)) prev.input += usage.inputTokens;
        if (Number.isFinite(usage.cacheReadTokens)) prev.cacheRead += usage.cacheReadTokens;
        if (Number.isFinite(usage.cacheWriteTokens)) prev.cacheWrite += usage.cacheWriteTokens;
        if (Number.isFinite(usage.outputTokens)) prev.output += usage.outputTokens;
      }
      cacheUsage.set(session.id, prev);
      void output.write({
        jsonrpc: '2.0',
        method: 'luma/usage/cache',
        params: { sessionId: session.id, input: prev.input, cacheRead: prev.cacheRead, cacheWrite: prev.cacheWrite, output: prev.output },
      }).catch(() => {});
    }
    if (event.type === 'session/title') {
      void output.write({ jsonrpc: '2.0', method: 'session/update', params: { sessionId: session.id,
        update: { sessionUpdate: 'session_info_update', title: event.data.title } } }).catch(() => {});
    }
    if (event.type === 'command/done') {
      const submissionId = activeCommandSubmissions.get(session.id)?.[0] ?? '';
      void output.write({
        jsonrpc: '2.0',
        method: 'luma/command/done',
        params: {
          sessionId: session.id,
          seq: event.seq,
          ...(submissionId ? { submissionId } : {}),
          command: event.data?.command ?? '',
          text: event.data?.text ?? '',
        },
      }).catch(() => {});
      changed(session.id);
    }
    if (event.type === 'agent/inbox/spliced') {
      const agent = owned.get(session.id);
      if (agent) {
        void output.write({
          jsonrpc: '2.0',
          method: 'luma/inbox/updated',
          params: { sessionId: session.id, queue: inboxSnapshot(agent) },
        }).catch(() => {});
      }
    }
    if (['permission/preset', 'sandbox/mode', 'approval/policy', 'plan/mode', 'goal/change', 'agent/preset'].includes(event.type)) {
      changed(session.id);
    }
  });

  ctx.on('permission-presets/catalog-changed', () => { for (const id of owned.keys()) changed(id); });

  ctx.on('agent/assistant-stream', ({ agent, frame }) => {
    const id = agent?.session?.id;
    if (!id || owned.get(id) !== agent || !frame) return;
    void output.write({
      jsonrpc: '2.0',
      method: 'luma/stream/frame',
      params: { sessionId: id, frame },
    }).catch(() => {});
  });

  ctx.on('user-questions/request', async (request, next) => {
    const agent = request?.agent;
    const id = agent?.session?.id;
    if (!id || owned.get(id) !== agent) return next();
    const rpcId = `luma-question-${++questionSeq}`;
    return new Promise((resolve, reject) => {
      const cleanup = () => {
        pendingQuestions.delete(rpcId);
        request.signal?.removeEventListener?.('abort', onAbort);
      };
      const onAbort = () => {
        cleanup();
        void output.write({
          jsonrpc: '2.0',
          method: 'luma/questions/cancelled',
          params: { sessionId: id, requestId: rpcId },
        }).catch(() => {});
        const err = new Error('User question aborted');
        err.name = 'AbortError';
        reject(err);
      };
      if (request.signal?.aborted) {
        onAbort();
        return;
      }
      request.signal?.addEventListener?.('abort', onAbort, { once: true });
      pendingQuestions.set(rpcId, {
        resolve: value => { cleanup(); resolve(value); },
        reject: err => { cleanup(); reject(err); },
      });
      void output.write({
        jsonrpc: '2.0',
        id: rpcId,
        method: 'luma/questions/request',
        params: {
          sessionId: id,
          requestId: rpcId,
          questions: request.questions ?? [],
          intent: request.intent ?? null,
          wait: request.wait ?? null,
        },
      }).catch(() => {});
    });
  });

  ctx.on('approval/request', (request, next) => {
    const agent = request?.agent;
    const id = agent?.session?.id;
    if (!id || owned.get(id) !== agent) return next();
    const reason = resolveApprovalReason(request);
    const toolName = typeof request?.toolName === 'string' ? request.toolName.trim() : '';
    if (request.callId !== undefined) {
      const cid = String(request.callId);
      approvalMetaByCallId.set(cid, { sessionId: id, toolName, reason });
      if (approvalMetaByCallId.size > 128) {
        const oldest = approvalMetaByCallId.keys().next().value;
        if (oldest !== undefined) approvalMetaByCallId.delete(oldest);
      }
      return next();
    }
    const rpcId = `luma-approval-${++approvalSeq}`;
    return new Promise(resolve => {
      let settled = false;
      const cleanup = () => {
        pendingApprovals.delete(rpcId);
        request.signal?.removeEventListener?.('abort', onAbort);
      };
      const finish = outcome => {
        if (settled) return;
        settled = true;
        cleanup();
        resolve(outcome);
      };
      const onAbort = () => {
        void output.write({
          jsonrpc: '2.0',
          method: 'luma/questions/cancelled',
          params: { sessionId: id, requestId: rpcId },
        }).catch(() => {});
        finish('cancelled');
      };
      if (request.signal?.aborted) {
        onAbort();
        return;
      }
      request.signal?.addEventListener?.('abort', onAbort, { once: true });
      pendingApprovals.set(rpcId, { sessionId: id, finish });
      void output.write({
        jsonrpc: '2.0',
        id: rpcId,
        method: 'session/request_permission',
        params: {
          sessionId: id,
          toolCall: {
            title: toolName || tr('工具授权', 'Tool permission'),
            ...(reason ? { rawInput: { reason } } : {}),
          },
          options: [
            { optionId: 'allow-once', name: 'Allow once', kind: 'allow_once' },
            { optionId: 'reject-once', name: 'Reject', kind: 'reject_once' },
          ],
        },
      }).catch(() => {});
    });
  });

  ctx.on('subagent/start', payload => {
    const parentId = payload?.parentSessionId;
    const childId = payload?.childSessionId;
    if (!parentId || !childId || !owned.has(parentId)) return;
    let map = trackedChildrenByParent.get(parentId);
    if (!map) { map = new Map(); trackedChildrenByParent.set(parentId, map); }
    map.set(childId, {
      id: childId,
      name: payload.label || payload.mode || payload.provider || 'subagent',
      agentType: payload.mode || payload.provider || 'subagent',
      nativeStatus: 'running',
    });
    scheduleSubagentPublish(parentId, true);
  });

  ctx.on('subagent/end', payload => {
    const parentId = payload?.parentSessionId;
    const childId = payload?.childSessionId;
    if (!parentId || !childId || !owned.has(parentId)) return;
    let map = trackedChildrenByParent.get(parentId);
    if (!map) { map = new Map(); trackedChildrenByParent.set(parentId, map); }
    const prev = map.get(childId) ?? { id: childId };
    map.set(childId, {
      ...prev,
      name: payload.label || prev.name || payload.mode || payload.provider || 'subagent',
      agentType: payload.mode || payload.provider || prev.agentType || 'subagent',
      nativeStatus: payload.status || 'completed',
      ...(typeof payload.error === 'string' && payload.error ? { detail: payload.error } : {}),
    });
    scheduleSubagentPublish(parentId, true);
  });

  ctx.on('subagent/catalog', payload => {
    const parentId = payload?.parentSessionId;
    if (!parentId || !owned.has(parentId)) return;
    scheduleSubagentPublish(parentId, true);
  });

  ctx.on('agent/inbox/inserted', ({ agent, message }) => {
    const id = agent.session.id;
    if (owned.get(id) !== agent || message.source?.kind !== 'user') return;
    const steerSub = steerAdmissions.get(message.id);
    if (typeof steerSub === 'string' && steerSub) {
      steerAdmissions.delete(message.id);
      void output.write({ jsonrpc: '2.0', method: 'luma/submissions/accepted', params: {
        sessionId: id, submissionId: steerSub, nativeMessageId: message.id,
      } }).catch(() => {});
      return;
    }
    const admission = admissions.get(id);
    if (!admission || admission.confirmed || typeof admission.submissionId !== 'string') return;
    admission.confirmed = true;
    void output.write({ jsonrpc: '2.0', method: 'luma/submissions/accepted', params: {
      sessionId: id, submissionId: admission.submissionId, nativeMessageId: message.id,
    } }).catch(() => {});
  });

  async function history(message) {
    const id = message.params?.sessionId;
    try {
      const agent = requireAgent(id);
      if (prompting.has(id) || agent.status !== 'idle') throw new Error('History hydration requires the native session to be idle');
      const query = ctx.get('sessionQuery');
      if (!query || typeof query.readSession !== 'function') throw new Error('DSH native session query service is unavailable');
      const snapshot = await query.readSession(id);
      if (snapshot.session.id !== id || requireAgent(id) !== agent) throw new Error('Native history identity changed during read');
      if (prompting.has(id) || agent.status !== 'idle') throw new Error('Native work started during history read; keep the live session');
      const projected = projectHistory(snapshot.events, sessionApi);
      if (projected.usage) {
        cacheUsage.set(id, {
          input: projected.usage.input,
          cacheRead: projected.usage.cacheRead,
          cacheWrite: projected.usage.cacheWrite,
          output: projected.usage.output,
          seenSeqs: new Set(snapshot.events.map(e => e.seq).filter(Number.isFinite)),
        });
      }
      const offset = message.params?.offset ?? 0;
      if (!Number.isSafeInteger(offset) || offset < 0 || offset > projected.messages.length) throw new Error('Invalid history page offset');
      if (message.params?.throughSeq !== undefined && message.params.throughSeq !== projected.throughSeq) throw new Error('Native history changed between pages; reconnect to synchronize');
      const end = Math.min(offset + 200, projected.messages.length);
      const subagents = offset === 0 ? await buildSubagentsSnapshot(id) : undefined;
      return { jsonrpc: '2.0', id: message.id, result: {
        sessionId: id,
        title: projected.title,
        throughSeq: projected.throughSeq,
        usage: projected.usage,
        messages: projected.messages.slice(offset, end),
        nextOffset: end < projected.messages.length ? end : null,
        ...(subagents !== undefined ? { subagents } : {}),
      } };
    } catch (error) {
      return { jsonrpc: '2.0', id: message.id, error: { code: -32602, message: String(error.message ?? error) } };
    }
  }

  async function control(message) {
    const p = message.params ?? {}; const id = p.sessionId;
    try {
      const agent = requireAgent(id);
      if (message.method === BALANCE) {
        const account = ctx.get('deepseekAccount');
        if (!account || typeof account.getBalance !== 'function') return { jsonrpc: '2.0', id: message.id, result: { status: 'unavailable', reason: 'DSH account service is unavailable' } };
        let value;
        try { value = await account.getBalance({ version: '0.0.0', locale: 'zh-CN', timezoneOffsetSeconds: -new Date().getTimezoneOffset() * 60 }); }
        catch { return { jsonrpc: '2.0', id: message.id, result: { status: 'unavailable', reason: 'DSH account query failed' } }; }
        if (!value || value.status !== 'ready') return { jsonrpc: '2.0', id: message.id, result: { status: 'unavailable', reason: 'DSH account is not signed in' } };
        const wallet = row => ({ currency: typeof row?.currency === 'string' ? row.currency : '', total: typeof row?.balance === 'string' ? row.balance : '' });
        return { jsonrpc: '2.0', id: message.id, result: { status: 'ready',
          wallets: (value.value || []).map(wallet), bonus: (value.bonusWallets || []).map(wallet) } };
      }
      if (message.method === PREFIX + 'set') {
        if (prompting.has(id) || agent.status !== 'idle') throw new Error('Wait for the native session to become idle before changing permissions');
        const before = snapshot(id);
        if (!before.options.some(option => option.value === p.value)) throw new Error('Permission preset is not in the native catalog');
        service().set(agent.session, p.value);
        const actual = snapshot(id);
        if (actual.currentValue !== p.value) throw new Error('DSH did not confirm the selected permission preset');
        return { jsonrpc: '2.0', id: message.id, result: actual };
      }
      if (message.method === PREFIX + 'plan') {
        if (prompting.has(id) || agent.status !== 'idle') throw new Error('Wait for the native session to become idle before changing plan mode');
        if (typeof p.enabled !== 'boolean') throw new Error('planMode enabled boolean is required');
        const planMode = ctx.get('planMode');
        if (!planMode || typeof planMode.set !== 'function' || typeof planMode.get !== 'function') throw new Error('DSH planMode service is unavailable');
        planMode.set(agent, p.enabled);
        const actual = snapshot(id);
        if (actual.planActive !== p.enabled) throw new Error('DSH did not confirm the requested plan mode');
        return { jsonrpc: '2.0', id: message.id, result: actual };
      }
      if (message.method === PREFIX + 'read') {
        return { jsonrpc: '2.0', id: message.id, result: snapshot(id) };
      }
      if (message.method === 'luma/presets/read') {
        return { jsonrpc: '2.0', id: message.id, result: {
          sessionId: id,
          currentPreset: getAgentPreset(agent),
          changeable: canChangePreset(agent),
          presets: PRESET_CATALOG,
        } };
      }
      if (message.method === 'luma/presets/set') {
        const result = await applyAgentPreset(agent, String(p.preset ?? '').trim(), true);
        changed(id);
        return { jsonrpc: '2.0', id: message.id, result };
      }
      if (message.method === 'luma/commands/list') {
        const commands = ctx.get('commands');
        const list = commands && typeof commands.list === 'function' ? commands.list(agent) : [];
        return { jsonrpc: '2.0', id: message.id, result: { commands: list } };
      }
      if (message.method === 'luma/commands/execute') {
        const commands = ctx.get('commands');
        if (!commands || typeof commands.execute !== 'function') throw new Error('DSH commands service is unavailable');
        let line = typeof p.text === 'string' ? p.text.trim() : '';
        if (!line) {
          const cmdName = String(p.name ?? '').trim().replace(/^\/+/, '');
          const cmdArgs = typeof p.args === 'string' ? p.args.trim() : '';
          line = cmdArgs ? `/${cmdName} ${cmdArgs}` : `/${cmdName}`;
        }
        if (!line.startsWith('/')) line = '/' + line;
        const submissionId = typeof p.submissionId === 'string' ? p.submissionId.trim() : '';
        const commandQueue = activeCommandSubmissions.get(id) ?? [];
        if (submissionId) {
          commandQueue.push(submissionId);
          activeCommandSubmissions.set(id, commandQueue);
        }
        const ac = new AbortController();
        let outcome;
        try {
          outcome = await commands.execute(agent, line, [], ac.signal);
        } finally {
          if (submissionId) {
            const idx = commandQueue.indexOf(submissionId);
            if (idx >= 0) commandQueue.splice(idx, 1);
            if (commandQueue.length === 0 && activeCommandSubmissions.get(id) === commandQueue) {
              activeCommandSubmissions.delete(id);
            }
          }
        }
        changed(id);
        if (!outcome) {
          return { jsonrpc: '2.0', id: message.id, result: { executed: false, handled: false } };
        }
        if (submissionId) {
          void output.write({
            jsonrpc: '2.0',
            method: 'luma/submissions/accepted',
            params: { sessionId: id, submissionId, nativeMessageId: `cmd:${submissionId}` },
          }).catch(() => {});
        }
        const res = outcome.result ?? {};
        return {
          jsonrpc: '2.0',
          id: message.id,
          result: {
            executed: true,
            handled: true,
            kind: res.kind ?? 'success',
            text: typeof res.text === 'string' ? res.text : '',
            running: agent.status !== 'idle',
            snapshot: snapshot(id),
          },
        };
      }
      if (message.method === 'luma/questions/answer') {
        const reqId = String(p.requestId ?? '');
        const q = pendingQuestions.get(reqId);
        if (!q) throw new Error('Question request is no longer pending');
        if (p.cancelled) {
          const err = new Error('User question cancelled');
          err.name = 'AbortError';
          q.reject(err);
        } else {
          const rawAnswers = p.answers;
          const answers = Array.isArray(rawAnswers)
            ? rawAnswers
            : Object.entries(rawAnswers ?? {}).map(([question, answer]) => ({ question, answer: String(answer ?? '') }));
          q.resolve({ answers });
        }
        return { jsonrpc: '2.0', id: message.id, result: { ok: true } };
      }
      if (message.method === 'luma/skills/list') {
        const skillsSvc = ctx.get('skills');
        let skills = [];
        if (skillsSvc && typeof skillsSvc.list === 'function') {
          const cwd = typeof p.cwd === 'string' && p.cwd ? p.cwd : process.cwd();
          const rawSkills = await skillsSvc.list({ cwd, scope: agent });
          skills = (Array.isArray(rawSkills) ? rawSkills : []).map(s => ({
            id: s.name,
            name: s.name,
            description: s.description ?? '',
            path: s.location ?? '',
          }));
        }
        return { jsonrpc: '2.0', id: message.id, result: { skills } };
      }
      if (message.method === 'luma/session/rename') {
        const title = String(p.title ?? '').trim();
        if (!title) throw new Error('Session title must be a non-empty string');
        agent.session.append('session/title', { title, source: { kind: 'user' } });
        return { jsonrpc: '2.0', id: message.id, result: { title } };
      }
      if (message.method === 'luma/prompt/steer') {
        const text = String(p.text ?? '');
        if (!text.trim()) throw new Error('Steer text must be non-empty');
        const msg = typeof llmApi?.createUserMessage === 'function'
          ? llmApi.createUserMessage({ content: [{ type: 'text', text }], source: { kind: 'user' } })
          : { id: randomUUID(), role: 'user', content: [{ type: 'text', text }], source: { kind: 'user' } };
        const submissionId = typeof p.submissionId === 'string' ? p.submissionId.trim() : '';
        if (submissionId) steerAdmissions.set(msg.id, submissionId);
        agent.steer(msg);
        return { jsonrpc: '2.0', id: message.id, result: { accepted: true, steered: true, nativeMessageId: msg.id } };
      }
      if (message.method === 'luma/inbox/read') {
        const queue = inboxSnapshot(agent);
        return { jsonrpc: '2.0', id: message.id, result: { sessionId: id, queue, items: queue } };
      }
      if (message.method === 'luma/inbox/mutate') {
        const messageId = String(p.messageId ?? p.id ?? '');
        if (!messageId) throw new Error('messageId is required');
        if (p.steer === true || p.action === 'steer') {
          const nextTurn = agent.inbox?.nextTurn ?? [];
          const found = nextTurn.find(m => m.id === messageId);
          if (!found) throw new Error('Message is no longer pending in next-turn inbox');
          agent.inbox.remove(messageId);
          agent.steer(found);
        } else if (!p.action || p.action === 'remove' || p.action === 'cancel' || p.action === 'delete') {
          const removed = agent.inbox?.remove?.(messageId);
          if (!removed) throw new Error('Message is no longer pending in inbox');
        } else {
          throw new Error(`Unsupported DSH inbox action: ${String(p.action)}`);
        }
        const queue = inboxSnapshot(agent);
        return { jsonrpc: '2.0', id: message.id, result: { sessionId: id, queue, items: queue } };
      }
      if (message.method === 'luma/subagents/read') {
        const subagents = await buildSubagentsSnapshot(id);
        return { jsonrpc: '2.0', id: message.id, result: { sessionId: id, subagents } };
      }
      throw new Error('Unknown Luma operation');
    } catch (error) {
      return { jsonrpc: '2.0', id: message.id, error: { code: -32602, message: String(error.message ?? error) } };
    }
  }

  const input = new ReadableStream({ async start(controller) {
    const reader = wire.readable.getReader();
    try {
      while (true) {
        const next = await reader.read(); if (next.done) break;
        const message = next.value;
        if (message && typeof message.id === 'string' && pendingQuestions.has(message.id) && !message.method) {
          const q = pendingQuestions.get(message.id);
          if (message.error || message.result?.cancelled) {
            const err = new Error(message.error?.message || 'User question cancelled');
            err.name = 'AbortError';
            q.reject(err);
          } else {
            q.resolve(message.result ?? { answers: [] });
          }
          continue;
        }
        if (message && typeof message.id === 'string' && pendingApprovals.has(message.id) && !message.method) {
          const pendingApproval = pendingApprovals.get(message.id);
          if (message.error || message.result?.outcome?.outcome === 'cancelled') {
            pendingApproval.finish('cancelled');
          } else {
            pendingApproval.finish(message.result?.outcome?.optionId === 'allow-once' ? 'allowed-once' : 'rejected');
          }
          continue;
        }
        if (message?.method === HISTORY) {
          if ('id' in message) {
            void history(message).then(reply => output.write(reply)).catch(() => {});
          }
          continue;
        }
        if (message?.method === BALANCE || (typeof message?.method === 'string' && message.method.startsWith('luma/'))) {
          if ('id' in message) {
            void control(message).then(reply => output.write(reply)).catch(() => {});
          }
          continue;
        }
        if (message && 'id' in message && typeof message.method === 'string') {
          pending.set(message.id, { method: message.method, sessionId: message.params?.sessionId });
          if (message.method === 'session/prompt') {
            const id = message.params?.sessionId;
            if (!prompting.has(id)) admissions.set(id, { requestId: message.id, submissionId: message.params?._meta?.lumaSubmissionId });
            prompting.add(id);
          }
        }
        controller.enqueue(message);
      }
      controller.close();
    } catch (error) { controller.error(error); }
  } });

  const stream = { readable: input, writable: new WritableStream({ async write(message) {
    const request = pending.get(message.id);
    if (request && !message.method) {
      pending.delete(message.id);
      if (request.method === 'initialize' && message.result) {
        message = {
          ...message,
          result: {
            ...message.result,
            _meta: {
              ...message.result._meta,
              lumaPermissions: capability(),
              lumaSubmissions: { protocol: 1 },
              lumaHistory: { protocol: 1, available: typeof ctx.get('sessionQuery')?.readSession === 'function' },
              lumaStream: { protocol: 1, available: true },
              lumaSteer: { protocol: 1, available: true },
              lumaQuestions: { protocol: 1, available: true },
              lumaCommands: { protocol: 1, available: Boolean(ctx.get('commands')) },
              lumaSkills: { protocol: 1, available: Boolean(ctx.get('skills')) },
              lumaSubagents: { protocol: 1, available: true },
              lumaPresets: { protocol: 1, available: true },
              lumaRename: { protocol: 1, available: true },
            },
          },
        };
      }
      if (['session/new', 'session/resume'].includes(request.method) && message.result) {
        const id = message.result.sessionId ?? request.sessionId; const agent = ctx.agents.get(id);
        if (agent) {
          owned.set(id, agent);
          const restoredPreset = getAgentPreset(agent);
          if (restoredPreset && restoredPreset !== 'standard') {
            void applyAgentPreset(agent, restoredPreset, false).catch(() => {});
          }
        }
      }
      if (request.method === 'session/prompt' && admissions.get(request.sessionId)?.requestId === message.id) {
        prompting.delete(request.sessionId); admissions.delete(request.sessionId);
      }
      if (request.method === 'session/close' && message.result) {
        const id = request.sessionId;
        owned.delete(id);
        prompting.delete(id);
        admissions.delete(id);
        cacheUsage.delete(id);
        trackedChildrenByParent.delete(id);
        activeCommandSubmissions.delete(id);
        toolCallsBySession.delete(id);
        for (const [cid, meta] of approvalMetaByCallId) {
          if (meta.sessionId === id) approvalMetaByCallId.delete(cid);
        }
        for (const [, pendingApproval] of [...pendingApprovals]) {
          if (pendingApproval.sessionId === id) pendingApproval.finish('cancelled');
        }
        const childTimer = childUpdateTimers.get(id);
        if (childTimer) {
          clearTimeout(childTimer);
          childUpdateTimers.delete(id);
        }
        agentSelectedPresets.delete(id);
        const dispose = agentPresetDisposers.get(id);
        if (typeof dispose === 'function') {
          try { dispose(); } catch {}
          agentPresetDisposers.delete(id);
        }
      }
    }
    let out = toolUpdate(message);
    if (out?.method === 'session/update') {
      const sid = out.params?.sessionId;
      const u = out.params?.update;
      if (sid && u && (u.sessionUpdate === 'tool_call' || u.sessionUpdate === 'tool_call_update') && typeof u.toolCallId === 'string' && u.toolCallId) {
        let sessionTools = toolCallsBySession.get(sid);
        if (!sessionTools) {
          sessionTools = new Map();
          toolCallsBySession.set(sid, sessionTools);
        }
        const prev = sessionTools.get(u.toolCallId) ?? {};
        sessionTools.set(u.toolCallId, {
          ...(prev.title || u.title ? { title: (typeof u.title === 'string' && u.title) ? u.title : prev.title } : {}),
          ...(u.kind !== undefined || prev.kind !== undefined ? { kind: u.kind ?? prev.kind } : {}),
          ...(u.rawInput !== undefined || prev.rawInput !== undefined ? { rawInput: u.rawInput !== undefined ? u.rawInput : prev.rawInput } : {}),
        });
        if (sessionTools.size > 128) {
          const oldest = sessionTools.keys().next().value;
          if (oldest !== undefined) sessionTools.delete(oldest);
        }
      }
    }
    if (out?.method === 'session/request_permission' && out.params?.toolCall) {
      const sid = out.params.sessionId;
      const tc = out.params.toolCall;
      const cid = typeof tc.toolCallId === 'string' ? tc.toolCallId : '';
      const cached = cid && sid ? toolCallsBySession.get(sid)?.get(cid) : undefined;
      const meta = cid ? approvalMetaByCallId.get(cid) : undefined;
      if (cid) approvalMetaByCallId.delete(cid);
      const nextToolCall = { ...tc };
      if (!nextToolCall.title) {
        const resolvedTitle = cached?.title || meta?.toolName;
        if (resolvedTitle) nextToolCall.title = resolvedTitle;
      }
      if (!nextToolCall.kind && cached?.kind) nextToolCall.kind = cached.kind;
      let raw = nextToolCall.rawInput !== undefined ? nextToolCall.rawInput : cached?.rawInput;
      if (meta?.reason) {
        if (raw && typeof raw === 'object' && !Array.isArray(raw)) {
          if (!raw.justification && !raw.reason && !raw.description) raw = { ...raw, reason: meta.reason };
        } else if (raw === undefined) {
          raw = { reason: meta.reason };
        }
      }
      if (raw !== undefined) nextToolCall.rawInput = raw;
      out = { ...out, params: { ...out.params, toolCall: nextToolCall } };
    }
    await output.write(out);
  } }) };
  native.apply(ctx, { ...config, stream });
}

export function projectHistory(events, api) {
  const rows = new Map(); const subagents = new Map();
  let title = ''; let turn = ''; let planMode; let goal;
  let totalInput = 0, totalCacheRead = 0, totalCacheWrite = 0, totalOutput = 0, hasUsage = false, lastUsed = null, lastSize = null;
  const add = row => rows.set(row.id, row);
  const body = blocks => (Array.isArray(blocks) ? blocks : []).map(b => {
    if (typeof b === 'string') return b;
    if (!b || typeof b !== 'object') return '';
    if (b.type === 'text' && typeof b.text === 'string') return b.text;
    if (b.type === 'content') return body(Array.isArray(b.content) ? b.content : [b.content]);
    if (typeof b.text === 'string') return b.text;
    if (typeof b.output === 'string') return b.output;
    return '';
  }).filter(Boolean).join('\n');
  for (const event of events) {
    const data = event.data; if (event.type === 'turn/start') turn = String(data.turn);
    if (event.type === 'plan/mode' && typeof data?.enabled === 'boolean') planMode = data.enabled;
    if (event.type === 'goal/change' && data && typeof data === 'object') goal = { active: Boolean(data.active), goal: data.goal ?? '' };
    if (event.type === 'subagent/start' && data?.sessionId) {
      subagents.set(data.sessionId, {
        id: data.sessionId,
        name: data.agentName || data.label || data.mode || 'subagent',
        role: data.agentName || data.mode || 'subagent',
        itemId: data.callId ? 'tool-' + data.callId : '',
        turnId: data.turn == null ? turn : String(data.turn),
        task: data.task ?? '',
        status: 'running',
        nativeStatus: 'running',
      });
    } else if (event.type === 'subagent/end' && data?.sessionId) {
      const prev = subagents.get(data.sessionId) ?? { id: data.sessionId };
      const nativeStatus = data.status ?? 'done';
      const status = ['done', 'completed', 'succeeded', 'ok'].includes(String(nativeStatus).toLowerCase())
        ? 'succeeded'
        : ['failed', 'error'].includes(String(nativeStatus).toLowerCase()) ? 'failed' : 'cancelled';
      subagents.set(data.sessionId, {
        ...prev,
        name: data.agentName || prev.name || 'subagent',
        status,
        nativeStatus,
      });
    }
    if (event.type === 'request/context' && Number.isFinite(data?.contextWindow) && data.contextWindow > 0) {
      lastSize = data.contextWindow;
    }
    if (data?.usage && typeof data.usage === 'object') {
      const u = data.usage;
      const inp = Number.isFinite(u.inputTokens) ? u.inputTokens : 0;
      const cr = Number.isFinite(u.cacheReadTokens) ? u.cacheReadTokens : 0;
      const cw = Number.isFinite(u.cacheWriteTokens) ? u.cacheWriteTokens : 0;
      const out = Number.isFinite(u.outputTokens) ? u.outputTokens : 0;
      if (inp + cr + cw + out > 0) {
        hasUsage = true;
        totalInput += inp;
        totalCacheRead += cr;
        totalCacheWrite += cw;
        totalOutput += out;
      }
      const promptTok = inp + cr + cw;
      if (Number.isFinite(u.totalTokens) && u.totalTokens > 0) lastUsed = u.totalTokens;
      else if (promptTok > 0) lastUsed = promptTok;
    }
    const source = { eventSeq: event.seq, time: event.time, turnId: data.turn == null ? turn : String(data.turn) };
    if (event.type === 'session/title' && typeof data.title === 'string') title = data.title;
    if (event.type === 'command/done' && typeof data?.text === 'string' && data.text.trim()) {
      const id = 'dsh-command-' + event.seq;
      add({ id, role: 'system', kind: 'status', title: '/' + (data.command || 'command'),
        text: data.text.trim(), status: 'completed', ...source });
    }
    if (event.type === 'tool/call') {
      const id = 'tool-' + data.callId;
      add({ ...rows.get(id), id, role: 'tool', kind: 'tool', text: data.name, toolName: data.name,
        toolKind: toolKindFor(data.name), input: data.arguments, status: 'unknown', ...source });
    }
    if (!api.isAppendSurfaceEvent(event)) continue;
    const message = api.deriveEventMessage(event);
    if (!message) continue;
    if (event.type === 'user/message' && message.source.kind === 'user') {
      const attachments = message.content.filter(b => b.type === 'image' || b.type === 'file');
      add({ id: 'dsh-message-' + message.id, role: 'user', text: body(message.content), status: 'completed',
        nativeMessageId: message.id, content: message.content, ...(attachments.length ? { detail: JSON.stringify(attachments) } : {}), ...source });
    } else if (event.type === 'assistant/message') {
      message.content.forEach((block, index) => {
        const id = 'dsh-message-' + message.id + '-' + index;
        if (block.type === 'text' || block.type === 'reasoning') {
          add({ id, role: block.type === 'reasoning' ? 'reasoning' : 'assistant', text: block.text,
            kind: block.type === 'reasoning' ? 'reasoning' : '', status: data.interrupted ? 'cancelled' : 'completed',
            nativeMessageId: message.id, ...source });
        } else if (block.type === 'tool-call') {
          add({ id: 'tool-' + block.id, role: 'tool', kind: 'tool', text: block.name, toolName: block.name,
            toolKind: toolKindFor(block.name), input: block.arguments, status: 'unknown', nativeMessageId: message.id, ...source });
        }
      });
    } else if (event.type === 'tool/result') {
      const id = 'tool-' + message.toolCallId;
      const status = data.error?.code === api.TOOL_OUTCOME_UNKNOWN ? 'unknown' : message.isError ? 'failed' : 'completed';
      add({ ...rows.get(id), id, role: 'tool', kind: 'tool', text: rows.get(id)?.text ?? tr('工具', 'Tool'),
        outcome: body(message.content), status, nativeResultMessageId: message.id,
        resultEventSeq: event.seq, ...(!rows.has(id) ? source : {}) });
    }
  }
  const usage = hasUsage ? { used: lastUsed, ...(lastSize ? { size: lastSize } : {}), input: totalInput, cacheRead: totalCacheRead, cacheWrite: totalCacheWrite, output: totalOutput } : null;
  return {
    messages: [...rows.values()],
    title,
    throughSeq: events.at(-1)?.seq ?? -1,
    usage,
    ...(planMode !== undefined ? { planMode } : {}),
    ...(goal !== undefined ? { goal } : {}),
    ...(subagents.size ? { subagents: [...subagents.values()] } : {}),
  };
}
