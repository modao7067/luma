function(request) {
  // Composed-tree traversal is adapted from CitroLabs ego-lite (MIT).
  // See ego-lite-MIT.txt. No browser/native bridge code is used here.
  let dispatched = false;
  const fail = (code, message) => { const error = Error(message); error.code = code; throw error; };
  if (request && request.action === '__finalizeTap') {
    const pending = window.__lumaPendingTap;
    if (!pending || pending.token !== request.token) return {ok: true, status: 'gone'};
    if (pending.clickDelivered || (pending.touchDelivered && pending.clickSuppressed)) {
      pending.cleanup();
      delete window.__lumaPendingTap;
      return {ok: true, status: 'delivered'};
    }
    if (!request.fallback) {
      return {ok: true, status: pending.touchDelivered ? 'waiting_click' : 'pending'};
    }
    pending.cleanup();
    delete window.__lumaPendingTap;
    if (pending.clickHost?.isConnected && pending.clickHost.tagName !== 'IFRAME' && pending.clickHost.tagName !== 'FRAME') {
      pending.fallbackClick();
      return {ok: true, status: 'fallback'};
    }
    return {ok: true, status: 'skipped'};
  }
  const parent = e => e?.assignedSlot || e?.parentElement || e?.getRootNode?.()?.host || null;
  const contains = (a, b) => { for (let e = b; e; e = parent(e)) if (e === a) return true; return false; };
  const shown = e => {
    if (!e?.isConnected || !Array.from(e.getClientRects()).some(r => r.width > 0 && r.height > 0)) return false;
    const ownStyle = e.ownerDocument.defaultView.getComputedStyle(e);
    if (ownStyle.visibility === 'hidden' || ownStyle.visibility === 'collapse') return false;
    for (let p = e; p; p = parent(p)) {
      const s = p.ownerDocument.defaultView.getComputedStyle(p);
      if (s.display === 'none' || Number(s.opacity) === 0) return false;
    }
    return true;
  };
  const disabled = e => {
    if (e.disabled || (typeof e.matches === 'function' && e.matches(':disabled'))) return true;
    let aria;
    for (let p = e; p; p = parent(p)) {
      if (p.inert) return true;
      if (aria === undefined && typeof p.getAttribute === 'function') {
        const value = p.getAttribute('aria-disabled')?.trim().toLowerCase();
        if (value === 'true' || value === 'false') aria = value === 'true';
      }
    }
    return aria === true;
  };
  const visible = (e, context) => shown(e) && context.frames.every(shown);
  const deepHit = (doc, x, y) => {
    let hit = doc.elementFromPoint(x, y);
    while (hit?.shadowRoot) {
      const inner = hit.shadowRoot.elementFromPoint(x, y);
      if (!inner || inner === hit) break;
      hit = inner;
    }
    return hit;
  };
  const toTopViewportRect = (e, context) => {
    const r = e.getBoundingClientRect();
    let left = r.left, top = r.top, width = r.width, height = r.height;
    for (let i = context.frames.length - 1; i >= 0; i--) {
      const frame = context.frames[i], fr = frame.getBoundingClientRect();
      const sx = fr.width / (frame.offsetWidth || fr.width || 1);
      const sy = fr.height / (frame.offsetHeight || fr.height || 1);
      left = fr.left + (frame.clientLeft + left) * sx;
      top = fr.top + (frame.clientTop + top) * sy;
      width *= sx;
      height *= sy;
    }
    return {
      x: Math.round(left),
      y: Math.round(top),
      width: Math.round(width),
      height: Math.round(height),
      inViewport: left + width > 0 && top + height > 0 && left < window.innerWidth && top < window.innerHeight,
    };
  };
  const findHitPoint = (e, context) => {
    const view = e.ownerDocument.defaultView;
    const r = e.getBoundingClientRect();
    const left = Math.max(0, r.left), top = Math.max(0, r.top);
    const right = Math.min(view.innerWidth, r.right), bottom = Math.min(view.innerHeight, r.bottom);
    if (right <= left || bottom <= top) return null;
    const points = [
      [(left + right) / 2, (top + bottom) / 2],
      [left + Math.min(6, (right - left) / 4), top + Math.min(6, (bottom - top) / 4)],
      [right - Math.min(6, (right - left) / 4), bottom - Math.min(6, (bottom - top) / 4)],
    ];
    for (const [px, py] of points) {
      let x = px, y = py;
      const hit = deepHit(e.ownerDocument, x, y);
      const labelHit = (e.tagName === 'INPUT' && e.labels?.length) ? Array.from(e.labels).some(l => contains(l, hit)) : false;
      if (!contains(e, hit) && !labelHit) continue;
      let frameOk = true;
      for (let i = context.frames.length - 1; i >= 0; i--) {
        const frame = context.frames[i], rect = frame.getBoundingClientRect();
        x = rect.left + (frame.clientLeft + x) * rect.width / (frame.offsetWidth || rect.width);
        y = rect.top + (frame.clientTop + y) * rect.height / (frame.offsetHeight || rect.height);
        if (!contains(frame, deepHit(frame.ownerDocument, x, y))) { frameOk = false; break; }
      }
      if (frameOk) return {topX: x, topY: y, localX: px, localY: py};
    }
    return null;
  };
  const hitTarget = (e, context) => Boolean(findHitPoint(e, context));
  const collect = () => {
    const roots = [], contexts = new Map(), entries = [], seen = new Set();
    let inaccessibleFrames = 0;
    const visit = (root, context) => {
      roots.push({root, context});
      for (const node of root.querySelectorAll('*')) {
        if (seen.has(node)) continue;
        seen.add(node); entries.push({node, context});
        if (node.shadowRoot) visit(node.shadowRoot, context);
        if (node.tagName === 'IFRAME' || node.tagName === 'FRAME') {
          let doc;
          try { doc = node.contentDocument; } catch (_) {}
          if (!doc?.documentElement) { inaccessibleFrames++; continue; }
          const child = {doc, url:doc.URL, frames:[...context.frames, node]};
          contexts.set(doc, child); visit(doc, child);
        }
      }
    };
    const top = {doc:document, url:location.href, frames:[]};
    contexts.set(document, top); visit(document, top);
    return {roots, contexts, entries, inaccessibleFrames};
  };
  const contextValid = context => {
    if (context.doc.URL !== context.url) return false;
    if (!context.frames.length) return context.doc === document;
    const frame = context.frames[context.frames.length - 1];
    try { return context.frames.every(f => f.isConnected) && frame.contentDocument === context.doc; } catch (_) { return false; }
  };

  const INTERACTIVE_ROLES = new Set([
    'button', 'link', 'menuitem', 'menuitemcheckbox', 'menuitemradio', 'option',
    'tab', 'checkbox', 'radio', 'switch', 'combobox', 'textbox', 'searchbox',
    'slider', 'spinbutton', 'treeitem',
  ]);
  const NATIVE_SELECTOR = 'a,button,input:not([type="hidden"]),textarea,select,summary';
  const hiddenWrappedControl = label => {
    if (label.tagName !== 'LABEL') return null;
    const ctrl = label.control || label.querySelector('input,select,textarea');
    return (ctrl && !shown(ctrl)) ? ctrl : null;
  };
  const isEditableRoot = e => Boolean(e.isContentEditable && (!parent(e) || !parent(e).isContentEditable));
  const isCandidateInteractive = (e, context) => {
    if (!visible(e, context)) return false;
    if (e.matches(NATIVE_SELECTOR)) return true;
    const role = e.getAttribute('role')?.trim().toLowerCase();
    if (role && INTERACTIVE_ROLES.has(role)) return true;
    if (isEditableRoot(e)) return true;
    if (hiddenWrappedControl(e)) return true;
    if (e.hasAttribute('onclick') || typeof e.onclick === 'function') return true;
    if (e.hasAttribute('tabindex') && e.tabIndex >= 0) return true;
    const tag = e.tagName;
    if (tag !== 'BODY' && tag !== 'HTML' && tag !== 'MAIN' && tag !== 'SECTION' && tag !== 'ARTICLE' && tag !== 'FORM' && tag !== 'UL' && tag !== 'OL' && tag !== 'TABLE') {
      const view = e.ownerDocument.defaultView;
      if (view.getComputedStyle(e).cursor === 'pointer') {
        const p = parent(e);
        const parentPointer = p && p.nodeType === 1 && p.ownerDocument?.defaultView?.getComputedStyle(p).cursor === 'pointer';
        if (!parentPointer && !e.querySelector(NATIVE_SELECTOR + ',[role="button"],[role="link"]')) return true;
      }
    }
    return false;
  };
  const filterDeduplicatedInteractive = entries => {
    const raw = entries.filter(({node, context}) => isCandidateInteractive(node, context));
    const nodeSet = new Set(raw.map(item => item.node));
    return raw.filter(({node}) => {
      if (node.matches(NATIVE_SELECTOR) || isEditableRoot(node)) {
        for (let p = parent(node); p; p = parent(p)) {
          if (nodeSet.has(p) && (p.tagName === 'BUTTON' || p.tagName === 'A') && !node.matches('input,textarea,select')) return false;
        }
        return true;
      }
      for (let p = parent(node); p; p = parent(p)) {
        if (nodeSet.has(p)) {
          const role = node.getAttribute('role')?.trim().toLowerCase();
          if (!role || !INTERACTIVE_ROLES.has(role) || p.tagName === 'BUTTON' || p.tagName === 'A' || p.getAttribute('role') === role) {
            return false;
          }
        }
      }
      return true;
    });
  };

  const cleanSpace = s => String(s || '').replace(/\s+/g, ' ').trim();
  const resolveLabelText = e => {
    const aria = e.getAttribute('aria-label');
    if (aria && aria.trim()) return cleanSpace(aria);
    const labelledBy = e.getAttribute('aria-labelledby');
    if (labelledBy) {
      const parts = labelledBy.split(/\s+/).map(id => e.ownerDocument.getElementById(id)?.innerText || '').filter(Boolean);
      if (parts.length) return cleanSpace(parts.join(' '));
    }
    if (e.labels?.length) {
      const l = Array.from(e.labels).map(x => x.innerText || '').join(' ');
      if (l.trim()) return cleanSpace(l);
    }
    return cleanSpace(e.getAttribute('placeholder') || e.getAttribute('title') || e.getAttribute('alt') || e.getAttribute('name') || '');
  };
  const implicitRole = e => {
    const explicit = e.getAttribute('role')?.trim().toLowerCase();
    if (explicit) return explicit;
    const wrapped = hiddenWrappedControl(e);
    const n = wrapped || e;
    const tag = n.tagName;
    if (tag === 'BUTTON' || tag === 'SUMMARY') return 'button';
    if (tag === 'A' && n.hasAttribute('href')) return 'link';
    if (/^H[1-6]$/.test(tag)) return 'heading';
    if (tag === 'SELECT') return 'combobox';
    if (tag === 'TEXTAREA' || isEditableRoot(n)) return 'textbox';
    if (tag === 'INPUT') {
      const t = (n.getAttribute('type') || 'text').toLowerCase();
      if (t === 'button' || t === 'submit' || t === 'reset') return 'button';
      if (t === 'checkbox') return 'checkbox';
      if (t === 'radio') return 'radio';
      if (t === 'search') return 'searchbox';
      if (t === 'range') return 'slider';
      if (t === 'number') return 'spinbutton';
      if (t !== 'hidden' && t !== 'file') return 'textbox';
    }
    return '';
  };
  const parseQuotedTerm = raw => {
    const t = raw.trim();
    if (t.length >= 2 && ((t.startsWith('"') && t.endsWith('"')) || (t.startsWith("'") && t.endsWith("'")))) {
      return {term: t.slice(1, -1), exact: true};
    }
    return {term: t, exact: false};
  };
  const matchSemanticText = (candidates, getValue, rawSpec) => {
    const {term, exact} = parseQuotedTerm(rawSpec);
    if (!term) fail('INVALID_SELECTOR', '语义定位值不能为空');
    const needle = cleanSpace(term);
    if (exact) return candidates.filter(item => cleanSpace(getValue(item)) === needle);
    const lower = needle.toLowerCase();
    const exactCI = candidates.filter(item => cleanSpace(getValue(item)).toLowerCase() === lower);
    if (exactCI.length) return exactCI;
    return candidates.filter(item => cleanSpace(getValue(item)).toLowerCase().includes(lower));
  };
  const query = (state, selector) => {
    if (typeof selector !== 'string' || !selector.trim()) fail('INVALID_SELECTOR', 'selector 必须是非空选择器');
    for (const {context} of state.roots) {
      if (!contextValid(context)) fail('STALE_DOCUMENT', 'frame 文档已变化，请重新 read');
    }
    const trimmed = selector.trim();
    const semantic = /^(text|label|placeholder|testid|role)=(.+)$/s.exec(trimmed);
    if (semantic) {
      const kind = semantic[1];
      const spec = semantic[2].trim();
      const visEntries = state.entries.filter(({node, context}) => visible(node, context));
      let matches = [];
      if (kind === 'testid') {
        matches = matchSemanticText(
          visEntries.filter(({node}) => node.hasAttribute('data-testid') || node.hasAttribute('data-test-id') || node.hasAttribute('data-test')),
          ({node}) => node.getAttribute('data-testid') || node.getAttribute('data-test-id') || node.getAttribute('data-test') || '',
          spec,
        );
      } else if (kind === 'placeholder') {
        matches = matchSemanticText(
          visEntries.filter(({node}) => (hiddenWrappedControl(node) || node).hasAttribute('placeholder')),
          ({node}) => (hiddenWrappedControl(node) || node).getAttribute('placeholder') || '',
          spec,
        );
      } else if (kind === 'label') {
        const pool = filterDeduplicatedInteractive(state.entries);
        matches = matchSemanticText(pool, ({node}) => {
          const w = hiddenWrappedControl(node);
          return resolveLabelText(node) || (w ? resolveLabelText(w) : '') || '';
        }, spec);
      } else if (kind === 'text') {
        const interactivePool = filterDeduplicatedInteractive(state.entries);
        matches = matchSemanticText(interactivePool, ({node}) => node.innerText || resolveLabelText(node) || node.value || '', spec);
        if (!matches.length) {
          const leafPool = visEntries.filter(({node}) => !node.children.length || /^H[1-6]|P|LI|SPAN|TD|TH|LABEL$/.test(node.tagName));
          matches = matchSemanticText(leafPool, ({node}) => node.innerText || node.textContent || '', spec);
        }
      } else if (kind === 'role') {
        const bracketMatch = /^([a-z0-9_-]+)\[(.+)\]$/is.exec(spec);
        const colonMatch = !bracketMatch ? /^([a-z0-9_-]+):(.+)$/is.exec(spec) : null;
        const roleName = (bracketMatch?.[1] || colonMatch?.[1] || spec).trim().toLowerCase();
        const nameSpec = bracketMatch?.[2] || colonMatch?.[2] || '';
        const byRole = filterDeduplicatedInteractive(state.entries).filter(({node}) => implicitRole(node) === roleName);
        const rolePool = byRole.length ? byRole : visEntries.filter(({node}) => implicitRole(node) === roleName);
        matches = nameSpec
          ? matchSemanticText(rolePool, ({node}) => node.innerText || resolveLabelText(node) || '', nameSpec)
          : rolePool;
      }
      if (matches.length > 1) fail('AMBIGUOUS_TARGET', '语义定位匹配多个元素，请补充更精确文本或使用 read 返回的 @N');
      return matches[0];
    }
    const matches = [];
    try {
      for (const {root, context} of state.roots) {
        for (const node of root.querySelectorAll(selector)) matches.push({node, context});
      }
    } catch (error) { if (typeof error.code === 'string') throw error; fail('INVALID_SELECTOR', 'CSS 选择器无效'); }
    if (matches.length > 1) fail('AMBIGUOUS_TARGET', 'CSS 选择器匹配多个元素，请使用唯一 selector 或 read 返回的 ref/index');
    return matches[0];
  };

  const describeElement = ({node:e, context}, index, includeUrls = false) => {
    const wrapped = hiddenWrappedControl(e);
    const stateNode = wrapped || e;
    const tag = e.tagName.toLowerCase();
    const inputType = (stateNode.getAttribute('type') || e.getAttribute('type') || null)?.toLowerCase() || null;
    const isPassword = stateNode.tagName === 'INPUT' && inputType === 'password';
    const role = e.getAttribute('role')?.trim().toLowerCase() || (wrapped ? wrapped.tagName.toLowerCase() : null);
    const label = resolveLabelText(e) || (wrapped ? resolveLabelText(wrapped) : '');
    const rawText = cleanSpace(e.innerText || label || '');
    const rect = toTopViewportRect(e, context);
    let value = null;
    if (!isPassword) {
      if (stateNode.tagName === 'INPUT' || stateNode.tagName === 'TEXTAREA' || stateNode.tagName === 'SELECT') {
        const v = String(stateNode.value ?? '');
        if (v && stateNode.type !== 'checkbox' && stateNode.type !== 'radio' && stateNode.type !== 'submit' && stateNode.type !== 'button') {
          value = v.slice(0, 200);
        }
      } else if (isEditableRoot(e)) {
        const v = cleanSpace(e.innerText);
        if (v) value = v.slice(0, 200);
      }
    }
    let checked = null;
    if (stateNode.tagName === 'INPUT' && (inputType === 'checkbox' || inputType === 'radio')) {
      checked = Boolean(stateNode.checked);
    } else {
      const ariaChecked = e.getAttribute('aria-checked')?.trim().toLowerCase();
      if (ariaChecked === 'true' || ariaChecked === 'false') checked = ariaChecked === 'true';
    }
    let options = undefined;
    if (stateNode.tagName === 'SELECT') {
      options = Array.from(stateNode.options || []).slice(0, 40).map(opt => ({
        value: String(opt.value ?? '').slice(0, 120),
        text: cleanSpace(opt.text || opt.label || opt.value).slice(0, 120),
        selected: Boolean(opt.selected),
      }));
    }
    const out = {
      index,
      ref: '@' + (index + 1),
      tag,
      text: (rawText || label).slice(0, 200),
      type: e.getAttribute('type') || (wrapped ? wrapped.getAttribute('type') : null),
      id: e.id || (wrapped?.id || null),
      disabled: disabled(stateNode),
      frameDepth: context.frames.length,
      inViewport: rect.inViewport,
      bounds: {x: rect.x, y: rect.y, width: rect.width, height: rect.height},
    };
    if (role) out.role = role;
    if (label && label !== out.text) out.label = label.slice(0, 200);
    if (value !== null) out.value = value;
    if (checked !== null) out.checked = checked;
    if (options) out.options = options;
    if (includeUrls && (e.tagName === 'A' || wrapped?.tagName === 'A')) {
      const linkEl = e.tagName === 'A' ? e : wrapped;
      const rawHref = String(linkEl.href || '');
      if (/^https:\/\/|^http:\/\/(localhost|127\.0\.0\.1|\[::1\])(?::|\/|$)/i.test(rawHref)) {
        out.href = rawHref.slice(0, 300);
      }
    }
    return out;
  };

  const extractMarkdown = rootDoc => {
    const skipTags = new Set(['SCRIPT', 'STYLE', 'TEMPLATE', 'NOSCRIPT', 'SVG']);
    const lines = [];
    const inlineText = node => {
      if (!node) return '';
      if (node.nodeType === 3) return node.nodeValue.replace(/\s+/g, ' ');
      if (node.nodeType !== 1) return '';
      if (skipTags.has(node.tagName) || !shown(node)) return '';
      if (node.tagName === 'BR') return '\n';
      if (node.tagName === 'CODE' && node.parentElement?.tagName !== 'PRE') {
        const t = cleanSpace(node.textContent);
        return t ? '`' + t + '`' : '';
      }
      if (node.tagName === 'A') {
        const label = cleanSpace(Array.from(node.childNodes).map(inlineText).join(''));
        const href = node.getAttribute('href') || '';
        if (!label) return '';
        if (href && !href.startsWith('javascript:') && !href.startsWith('#')) return `[${label}](${href})`;
        return label;
      }
      return Array.from(node.childNodes).map(inlineText).join('');
    };
    const walkBlock = (node, listDepth) => {
      if (!node) return;
      if (node.nodeType === 3) {
        const t = cleanSpace(node.nodeValue);
        if (t) lines.push(t);
        return;
      }
      if (node.nodeType !== 1 || skipTags.has(node.tagName) || !shown(node)) return;
      const tag = node.tagName;
      if (/^H[1-6]$/.test(tag)) {
        const t = cleanSpace(inlineText(node));
        if (t) lines.push('\n' + '#'.repeat(Number(tag[1])) + ' ' + t + '\n');
        return;
      }
      if (tag === 'PRE') {
        const code = (node.innerText || node.textContent || '').replace(/\n+$/, '');
        if (code.trim()) lines.push('\n```\n' + code + '\n```\n');
        return;
      }
      if (tag === 'UL' || tag === 'OL') {
        let idx = 1;
        for (const child of node.children) {
          if (child.tagName === 'LI' && shown(child)) {
            const prefix = '  '.repeat(listDepth) + (tag === 'OL' ? `${idx++}. ` : '- ');
            const own = [];
            for (const c of child.childNodes) {
              if (c.nodeType === 1 && (c.tagName === 'UL' || c.tagName === 'OL')) continue;
              own.push(inlineText(c));
            }
            const itemText = cleanSpace(own.join(''));
            if (itemText) lines.push(prefix + itemText);
            for (const c of child.children) {
              if (c.tagName === 'UL' || c.tagName === 'OL') walkBlock(c, listDepth + 1);
            }
          }
        }
        return;
      }
      if (tag === 'TABLE') {
        const rows = [];
        for (const tr of node.querySelectorAll('tr')) {
          if (!shown(tr)) continue;
          const cells = Array.from(tr.querySelectorAll(':scope > th, :scope > td')).map(c => cleanSpace(inlineText(c)).replace(/\|/g, '\\|'));
          if (cells.some(Boolean)) rows.push(cells);
        }
        if (rows.length) {
          const cols = Math.max(...rows.map(r => r.length));
          const pad = r => Array.from({length: cols}, (_, i) => r[i] || '');
          lines.push('\n| ' + pad(rows[0]).join(' | ') + ' |');
          lines.push('| ' + Array.from({length: cols}, () => '---').join(' | ') + ' |');
          for (let i = 1; i < rows.length; i++) lines.push('| ' + pad(rows[i]).join(' | ') + ' |');
          lines.push('');
        }
        return;
      }
      if (tag === 'BLOCKQUOTE') {
        const t = cleanSpace(inlineText(node));
        if (t) lines.push('> ' + t);
        return;
      }
      if (tag === 'P' || tag === 'SUMMARY' || tag === 'FIGCAPTION') {
        const t = cleanSpace(inlineText(node));
        if (t) lines.push(t + '\n');
        return;
      }
      for (const child of node.childNodes) walkBlock(child, listDepth);
    };
    const startNode = rootDoc?.nodeType === 9 ? rootDoc.body : rootDoc;
    if (startNode) walkBlock(startNode, 0);
    return lines.join('\n').replace(/\n{3,}/g, '\n\n').trim();
  };

  const buildSnapshotState = scopeSelector => {
    const state = collect();
    let scopedNode = null;
    let candidateEntries = state.entries;
    if (typeof scopeSelector === 'string' && scopeSelector.trim()) {
      const scoped = query(state, scopeSelector);
      if (!scoped?.node?.isConnected) fail('TARGET_MISSING', '未找到限定作用域容器');
      scopedNode = scoped.node;
      candidateEntries = state.entries.filter(item => contains(scopedNode, item.node));
    }
    const snapshot = String(Date.now()) + '-' + Math.random().toString(36).slice(2);
    const interactive = filterDeduplicatedInteractive(candidateEntries);
    const elements = interactive.slice(0, 300);
    const identities = new WeakMap(state.entries.map(entry => [entry.node, entry]));
    const saved = {...state, snapshot, document, url:location.href, elements, identities, totalInteractive: interactive.length, scopedNode};
    window.__lumaRead = saved;
    return saved;
  };

  const resolvePointTarget = (topX, topY) => {
    if (typeof topX !== 'number' || typeof topY !== 'number' || !Number.isFinite(topX) || !Number.isFinite(topY)) {
      fail('INVALID_COORDINATES', '坐标 x/y 必须是有限数值');
    }
    let doc = document, x = topX, y = topY;
    const frames = [];
    while (true) {
      if (x < 0 || y < 0 || x > (doc.defaultView?.innerWidth || 0) || y > (doc.defaultView?.innerHeight || 0)) {
        fail('OUT_OF_VIEWPORT', '坐标超出当前可视窗口范围');
      }
      const hit = deepHit(doc, x, y);
      if (!hit) fail('TARGET_MISSING', '该坐标处未命中页面元素');
      if (hit.tagName === 'IFRAME' || hit.tagName === 'FRAME') {
        let childDoc;
        try { childDoc = hit.contentDocument; } catch (_) {}
        if (!childDoc?.documentElement) return {node: hit, context: {doc, url: doc.URL, frames}, point: {x, y}};
        const rect = hit.getBoundingClientRect();
        const sx = (hit.offsetWidth || rect.width || 1) / (rect.width || 1);
        const sy = (hit.offsetHeight || rect.height || 1) / (rect.height || 1);
        x = (x - rect.left) * sx - hit.clientLeft;
        y = (y - rect.top) * sy - hit.clientTop;
        frames.push(hit);
        doc = childDoc;
        continue;
      }
      return {node: hit, context: {doc, url: doc.URL, frames}, point: {x, y}};
    }
  };

  const NATIVE_CLICK_TAGS = new Set(['A', 'BUTTON', 'INPUT', 'SUMMARY', 'LABEL', 'OPTION']);
  const dispatchRealisticClick = (e, clientX, clientY) => {
    const view = e.ownerDocument.defaultView;
    const r = e.getBoundingClientRect();
    const cx = Number.isFinite(clientX) ? clientX : (r.left + r.width / 2);
    const cy = Number.isFinite(clientY) ? clientY : (r.top + r.height / 2);
    const init = {bubbles: true, cancelable: true, composed: true, view, clientX: cx, clientY: cy, button: 0, buttons: 1};
    let touchCanceled = false;
    try {
      if (typeof view.PointerEvent === 'function') {
        e.dispatchEvent(new view.PointerEvent('pointerdown', {...init, pointerId: 1, pointerType: 'touch', isPrimary: true}));
      }
      if (typeof view.Touch === 'function' && typeof view.TouchEvent === 'function') {
        const touch = new view.Touch({identifier: 1, target: e, clientX: cx, clientY: cy, pageX: cx + (view.scrollX || 0), pageY: cy + (view.scrollY || 0)});
        if (!e.dispatchEvent(new view.TouchEvent('touchstart', {bubbles: true, cancelable: true, composed: true, view, touches: [touch], targetTouches: [touch], changedTouches: [touch]}))) {
          touchCanceled = true;
        }
      }
      if (!touchCanceled) e.dispatchEvent(new view.MouseEvent('mousedown', init));
      if (typeof e.focus === 'function') e.focus({preventScroll: true});
      if (typeof view.PointerEvent === 'function') {
        e.dispatchEvent(new view.PointerEvent('pointerup', {...init, buttons: 0, pointerId: 1, pointerType: 'touch', isPrimary: true}));
      }
      if (typeof view.Touch === 'function' && typeof view.TouchEvent === 'function') {
        const touch = new view.Touch({identifier: 1, target: e, clientX: cx, clientY: cy, pageX: cx + (view.scrollX || 0), pageY: cy + (view.scrollY || 0)});
        if (!e.dispatchEvent(new view.TouchEvent('touchend', {bubbles: true, cancelable: true, composed: true, view, touches: [], targetTouches: [], changedTouches: [touch]}))) {
          touchCanceled = true;
        }
      }
      if (!touchCanceled) e.dispatchEvent(new view.MouseEvent('mouseup', {...init, buttons: 0}));
    } catch (_) {}
    if (!touchCanceled) {
      if (NATIVE_CLICK_TAGS.has(e.tagName) && typeof e.click === 'function') e.click();
      else e.dispatchEvent(new view.MouseEvent('click', {...init, buttons: 0, detail: 1}));
    }
  };

  const setTextFieldValue = (e, nextValue) => {
    const proto = e.tagName === 'TEXTAREA'
      ? e.ownerDocument.defaultView.HTMLTextAreaElement?.prototype
      : e.tagName === 'SELECT'
        ? e.ownerDocument.defaultView.HTMLSelectElement?.prototype
        : e.ownerDocument.defaultView.HTMLInputElement?.prototype;
    const setter = Object.getOwnPropertyDescriptor(proto || Object.getPrototypeOf(e), 'value')?.set
      || Object.getOwnPropertyDescriptor(Object.getPrototypeOf(e), 'value')?.set;
    if (setter) setter.call(e, nextValue); else e.value = nextValue;
    const EventClass = e.ownerDocument.defaultView.Event;
    e.dispatchEvent(new EventClass('input', {bubbles:true}));
    e.dispatchEvent(new EventClass('change', {bubbles:true}));
  };

  const triggerSubmit = e => {
    const view = e.ownerDocument.defaultView;
    try {
      const keyInit = {bubbles: true, cancelable: true, key: 'Enter', code: 'Enter', keyCode: 13, which: 13};
      e.dispatchEvent(new view.KeyboardEvent('keydown', keyInit));
      e.dispatchEvent(new view.KeyboardEvent('keypress', keyInit));
      e.dispatchEvent(new view.KeyboardEvent('keyup', keyInit));
    } catch (_) {}
    const form = e.form || e.closest?.('form');
    if (form) {
      if (typeof form.requestSubmit === 'function') form.requestSubmit();
      else form.submit();
    }
  };

  const resolveKeySpec = rawKey => {
    const raw = String(rawKey || '').trim();
    if (!raw) fail('INVALID_KEY', '按键名称不能为空');
    const aliases = {
      enter: {key: 'Enter', code: 'Enter', keyCode: 13},
      return: {key: 'Enter', code: 'Enter', keyCode: 13},
      esc: {key: 'Escape', code: 'Escape', keyCode: 27},
      escape: {key: 'Escape', code: 'Escape', keyCode: 27},
      tab: {key: 'Tab', code: 'Tab', keyCode: 9},
      space: {key: ' ', code: 'Space', keyCode: 32},
      backspace: {key: 'Backspace', code: 'Backspace', keyCode: 8},
      delete: {key: 'Delete', code: 'Delete', keyCode: 46},
      arrowup: {key: 'ArrowUp', code: 'ArrowUp', keyCode: 38},
      up: {key: 'ArrowUp', code: 'ArrowUp', keyCode: 38},
      arrowdown: {key: 'ArrowDown', code: 'ArrowDown', keyCode: 40},
      down: {key: 'ArrowDown', code: 'ArrowDown', keyCode: 40},
      arrowleft: {key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37},
      left: {key: 'ArrowLeft', code: 'ArrowLeft', keyCode: 37},
      arrowright: {key: 'ArrowRight', code: 'ArrowRight', keyCode: 39},
      right: {key: 'ArrowRight', code: 'ArrowRight', keyCode: 39},
      home: {key: 'Home', code: 'Home', keyCode: 36},
      end: {key: 'End', code: 'End', keyCode: 35},
      pageup: {key: 'PageUp', code: 'PageUp', keyCode: 33},
      pagedown: {key: 'PageDown', code: 'PageDown', keyCode: 34},
    };
    const parts = raw.split('+').map(p => p.trim()).filter(Boolean);
    let ctrlKey = false, altKey = false, shiftKey = false, metaKey = false;
    let main = raw;
    if (parts.length > 1) {
      main = parts[parts.length - 1];
      for (let i = 0; i < parts.length - 1; i++) {
        const mod = parts[i].toLowerCase();
        if (mod === 'control' || mod === 'ctrl') ctrlKey = true;
        else if (mod === 'alt' || mod === 'option') altKey = true;
        else if (mod === 'shift') shiftKey = true;
        else if (mod === 'meta' || mod === 'cmd' || mod === 'command') metaKey = true;
      }
    }
    const mapped = aliases[main.toLowerCase()];
    if (mapped) return {...mapped, ctrlKey, altKey, shiftKey, metaKey};
    const ch = main.length === 1 ? main : main;
    const upper = ch.length === 1 ? ch.toUpperCase().charCodeAt(0) : 0;
    const code = ch.length === 1 && /[a-zA-Z]/.test(ch) ? 'Key' + ch.toUpperCase() : (ch.length === 1 && /[0-9]/.test(ch) ? 'Digit' + ch : ch);
    return {key: ch, code, keyCode: upper, ctrlKey, altKey, shiftKey, metaKey};
  };

  const dispatchKeyPress = (e, spec) => {
    const view = e.ownerDocument?.defaultView || window;
    const init = {
      bubbles: true,
      cancelable: true,
      composed: true,
      key: spec.key,
      code: spec.code,
      keyCode: spec.keyCode,
      which: spec.keyCode,
      ctrlKey: Boolean(spec.ctrlKey),
      altKey: Boolean(spec.altKey),
      shiftKey: Boolean(spec.shiftKey),
      metaKey: Boolean(spec.metaKey),
    };
    e.dispatchEvent(new view.KeyboardEvent('keydown', init));
    if (spec.key === 'Enter' || spec.key.length === 1) {
      e.dispatchEvent(new view.KeyboardEvent('keypress', init));
    }
    e.dispatchEvent(new view.KeyboardEvent('keyup', init));
  };

  const dispatchRealisticHover = (e, clientX, clientY) => {
    const view = e.ownerDocument.defaultView;
    const r = e.getBoundingClientRect();
    const cx = Number.isFinite(clientX) ? clientX : (r.left + r.width / 2);
    const cy = Number.isFinite(clientY) ? clientY : (r.top + r.height / 2);
    const init = {bubbles: true, cancelable: true, composed: true, view, clientX: cx, clientY: cy};
    try {
      if (typeof view.PointerEvent === 'function') {
        e.dispatchEvent(new view.PointerEvent('pointerover', {...init, pointerId: 1, pointerType: 'mouse', isPrimary: true}));
        e.dispatchEvent(new view.PointerEvent('pointerenter', {...init, bubbles: false, pointerId: 1, pointerType: 'mouse', isPrimary: true}));
        e.dispatchEvent(new view.PointerEvent('pointermove', {...init, pointerId: 1, pointerType: 'mouse', isPrimary: true}));
      }
      e.dispatchEvent(new view.MouseEvent('mouseover', init));
      e.dispatchEvent(new view.MouseEvent('mouseenter', {...init, bubbles: false}));
      e.dispatchEvent(new view.MouseEvent('mousemove', init));
    } catch (_) {}
  };

  try {
    if (request.action === 'selection') {
      const state = collect();
      const parts = [];
      const seenDocs = new Set();
      for (const {context} of state.roots) {
        if (!context?.doc || seenDocs.has(context.doc) || !contextValid(context)) continue;
        seenDocs.add(context.doc);
        const sel = String(context.doc.defaultView?.getSelection?.()?.toString?.() || '').trim();
        if (sel) parts.push(sel);
      }
      return {ok: true, dispatched: false, selection: parts.join('\n\n').slice(0, 8000), url: location.href, title: document.title};
    }

    if (request.action === 'read') {
      const saved = buildSnapshotState(request.scopeSelector);
      const interactiveOnly = Boolean(request.interactiveOnly);
      const includeUrls = Boolean(request.includeUrls);
      let text = '';
      let fullMarkdown = '';
      if (!interactiveOnly) {
        if (saved.scopedNode) {
          text = saved.scopedNode.innerText || '';
          fullMarkdown = extractMarkdown(saved.scopedNode);
        } else {
          text = document.body?.innerText || '';
          for (const {root, context} of saved.roots) {
            if (root === document || text.length > 60000 || !context.frames.every(shown)) continue;
            if (root.nodeType === 9) text += '\n' + (root.body?.innerText || '');
            else {
              const walker = root.ownerDocument.createTreeWalker(root, 4);
              let node;
              while ((node = walker.nextNode()) && text.length <= 60000) {
                const p = node.parentElement;
                if (p && !p.closest('script,style,template,noscript') && shown(p)) text += '\n' + node.textContent.trim();
              }
            }
          }
          fullMarkdown = extractMarkdown(document);
        }
      }
      const offset = Math.max(0, Number(request.offset || 0) || 0);
      const maxChars = Math.min(60000, Math.max(200, Number(request.maxChars || 60000) || 60000));
      const pagedText = interactiveOnly ? '' : text.slice(offset, offset + maxChars);
      const pagedMarkdown = interactiveOnly ? '' : fullMarkdown.slice(offset, offset + maxChars);
      return {
        ok: true,
        snapshot: saved.snapshot,
        url: location.href,
        title: document.title,
        text: pagedText,
        markdown: pagedMarkdown,
        offset,
        totalTextLength: text.length,
        totalMarkdownLength: fullMarkdown.length,
        truncated: !interactiveOnly && offset + maxChars < text.length,
        elementsTruncated: saved.totalInteractive > 300,
        inaccessibleFrames: saved.inaccessibleFrames,
        viewportWidth: Math.round(window.innerWidth || 0),
        viewportHeight: Math.round(window.innerHeight || 0),
        scrollY: Math.round(window.scrollY || 0),
        maxScrollY: Math.max(0, Math.round((document.documentElement?.scrollHeight || 0) - window.innerHeight)),
        elements: saved.elements.map((item, idx) => describeElement(item, idx, includeUrls)),
      };
    }

    if (request.action === 'wait') {
      const state = collect(), kind = request.state || 'visible';
      const target = query(state, request.selector);
      if (!target) return {ok:true, matched:kind === 'hidden' || kind === 'detached', state:kind, reason:'NOT_FOUND'};
      const {node:e, context} = target;
      const isVisible = visible(e, context);
      const matched = kind === 'attached' || (kind === 'hidden' && !isVisible) ||
        (kind === 'visible' && isVisible) || (kind === 'enabled' && isVisible && !disabled(e)) ||
        (kind === 'actionable' && isVisible && !disabled(e) && hitTarget(e, context));
      return {ok:true, matched, state:kind, reason:matched ? 'MATCHED' : !isVisible ? 'NOT_VISIBLE' : disabled(e) ? 'DISABLED' : 'NOT_ACTIONABLE'};
    }

    if (request.action === 'scroll') {
      const dir = String(request.direction || 'down').toLowerCase();
      if (request.selector !== undefined || request.ref !== undefined || request.index !== undefined) {
        const saved = (window.__lumaRead && window.__lumaRead.document === document && window.__lumaRead.url === location.href)
          ? window.__lumaRead
          : buildSnapshotState();
        let target;
        if (request.selector !== undefined) target = query(collect(), request.selector);
        else if (request.ref !== undefined) target = saved.elements[Number(String(request.ref).slice(1)) - 1];
        else target = saved.elements[Number(request.index)];
        if (!target?.node?.isConnected) fail('TARGET_MISSING', '滚动目标不存在');
        const el = target.node;
        if (request.intoView || (el.scrollHeight <= el.clientHeight && el.scrollWidth <= el.clientWidth)) {
          el.scrollIntoView({block: 'center', inline: 'center', behavior: 'instant'});
        } else {
          const amt = Number(request.amount) || Math.round((dir === 'left' || dir === 'right' ? el.clientWidth : el.clientHeight) * 0.75) || 320;
          if (dir === 'down') el.scrollTop += amt;
          else if (dir === 'up') el.scrollTop -= amt;
          else if (dir === 'right') el.scrollLeft += amt;
          else if (dir === 'left') el.scrollLeft -= amt;
          else if (dir === 'top') el.scrollTop = 0;
          else if (dir === 'bottom') el.scrollTop = el.scrollHeight;
        }
      } else {
        const amt = Number(request.amount) || Math.round((dir === 'left' || dir === 'right' ? window.innerWidth : window.innerHeight) * 0.75) || 480;
        if (dir === 'down') window.scrollBy(0, amt);
        else if (dir === 'up') window.scrollBy(0, -amt);
        else if (dir === 'right') window.scrollBy(amt, 0);
        else if (dir === 'left') window.scrollBy(-amt, 0);
        else if (dir === 'top') window.scrollTo(0, 0);
        else if (dir === 'bottom') window.scrollTo(0, document.documentElement?.scrollHeight || document.body?.scrollHeight || 0);
        else fail('INVALID_DIRECTION', 'direction 仅支持 up/down/left/right/top/bottom');
      }
      return {
        ok: true,
        dispatched: false,
        scrollX: Math.round(window.scrollX || 0),
        scrollY: Math.round(window.scrollY || 0),
        maxScrollY: Math.max(0, Math.round((document.documentElement?.scrollHeight || 0) - window.innerHeight)),
      };
    }

    if (request.action === 'press' && request.selector === undefined && request.ref === undefined && request.index === undefined && request.x === undefined) {
      const spec = resolveKeySpec(request.key);
      const liveMode = request.snapshot === '__live__';
      const reuseSnapshot = Boolean(request.reuseSnapshot || liveMode);
      if (request.snapshot !== undefined && !liveMode) {
        const read = window.__lumaRead;
        if (!read || request.snapshot !== read.snapshot || read.document !== document || read.url !== location.href) {
          fail('STALE_SNAPSHOT', '页面快照已过期，请重新 read');
        }
      }
      if (!reuseSnapshot) delete window.__lumaRead;
      let active = document.activeElement;
      while (active && (active.tagName === 'IFRAME' || active.tagName === 'FRAME')) {
        try {
          if (active.contentDocument?.activeElement) active = active.contentDocument.activeElement;
          else break;
        } catch (_) { break; }
      }
      const targetEl = active || document.body || document.documentElement;
      dispatched = true;
      dispatchKeyPress(targetEl, spec);
      if (request.submit && spec.key === 'Enter') triggerSubmit(targetEl);
      return {ok: true, dispatched: true, key: spec.key};
    }

    const MUTATING_ACTIONS = new Set(['click', 'fill', 'type', 'select', 'check', 'uncheck', 'hover', 'press']);
    if (!MUTATING_ACTIONS.has(request.action)) fail('UNKNOWN_ACTION', '未知页面操作');

    const coordinateMode = request.x !== undefined && request.y !== undefined;
    const liveMode = request.snapshot === '__live__';
    const reuseSnapshot = Boolean(request.reuseSnapshot || liveMode);
    let read = window.__lumaRead;
    if (liveMode && (!read || read.document !== document || read.url !== location.href)) {
      if (request.ref !== undefined || request.index !== undefined) {
        fail('STALE_SNAPSHOT', '页面已变化，请先重新 read 获取最新 @N 编号');
      }
      read = buildSnapshotState();
    }
    if (!coordinateMode || request.snapshot !== undefined) {
      if (!liveMode && (!read || request.snapshot !== read.snapshot || read.document !== document || read.url !== location.href)) {
        fail('STALE_SNAPSHOT', '页面快照已过期，请重新 read');
      }
    }

    let target, coordPoint = null;
    if (coordinateMode) {
      const resolved = resolvePointTarget(Number(request.x), Number(request.y));
      target = {node: resolved.node, context: resolved.context};
      coordPoint = resolved.point;
    } else if (request.selector !== undefined) {
      target = query(collect(), request.selector);
      if (target && !liveMode && !read.identities.has(target.node)) fail('STALE_NODE', 'selector 对应节点在 read 后新增或替换，请重新 read');
    } else if (request.ref !== undefined) {
      if (typeof request.ref !== 'string' || !/^@[1-9]\d*$/.test(request.ref)) fail('INVALID_REF', 'ref 必须使用 read 返回的 @N');
      target = read.elements[Number(request.ref.slice(1)) - 1];
    } else {
      target = read.elements[request.index];
    }
    if (!target) fail('TARGET_MISSING', '元素不存在或已移除，请重新 read');
    const resolvedEntry = (!coordinateMode && read?.identities?.get(target.node)) || target;
    let {node:e, context} = resolvedEntry;
    if (!contextValid(context) || e.ownerDocument !== context.doc) fail('STALE_DOCUMENT', '元素文档已变化，请重新 read');
    if (!e.isConnected) fail('STALE_NODE', '元素已移除，请重新 read');

    let clickHost = e;
    if (e.tagName === 'INPUT' && (e.type === 'checkbox' || e.type === 'radio') && !shown(e) && e.labels?.length) {
      const visLabel = Array.from(e.labels).find(l => visible(l, context));
      if (visLabel) clickHost = visLabel;
    }
    const wrappedCtrl = hiddenWrappedControl(e);
    if (!visible(clickHost, context)) fail('NOT_VISIBLE', '元素不可见');
    if (disabled(wrappedCtrl || e)) fail('DISABLED', '元素已禁用');

    if (request.action === 'fill' || request.action === 'type') {
      if (coordinateMode && !['INPUT', 'TEXTAREA', 'SELECT'].includes(e.tagName) && !e.isContentEditable) {
        const inner = e.closest?.('input,textarea,select,[contenteditable="true"]') || e.querySelector?.('input,textarea,select,[contenteditable="true"]');
        if (inner) e = inner;
      }
      const editableContent = Boolean(e.isContentEditable);
      if (!editableContent && (!['INPUT', 'TEXTAREA', 'SELECT'].includes(e.tagName) || e.type === 'file' || e.readOnly)) {
        fail('NOT_EDITABLE', '不是可输入字段');
      }
    }
    if (request.action === 'select') {
      const selNode = wrappedCtrl?.tagName === 'SELECT' ? wrappedCtrl : e;
      const role = selNode.getAttribute('role')?.trim().toLowerCase();
      if (selNode.tagName !== 'SELECT' && role !== 'listbox' && role !== 'combobox') {
        fail('NOT_SELECTABLE', '目标不是下拉选择控件');
      }
    }
    if (request.action === 'check' || request.action === 'uncheck') {
      const ctrl = wrappedCtrl || e;
      const role = ctrl.getAttribute('role')?.trim().toLowerCase();
      const isCheckInput = ctrl.tagName === 'INPUT' && (ctrl.type === 'checkbox' || ctrl.type === 'radio');
      const isAriaCheck = role === 'checkbox' || role === 'switch' || role === 'menuitemcheckbox' || role === 'radio';
      if (!isCheckInput && !isAriaCheck) fail('NOT_CHECKABLE', '目标不是可勾选控件');
    }

    let hitPoint = null;
    if (!coordinateMode) {
      for (const frame of context.frames) frame.scrollIntoView({block:'center', inline:'center', behavior:'instant'});
      clickHost.scrollIntoView({block:'center', inline:'center', behavior:'instant'});
      hitPoint = findHitPoint(clickHost, context);
      if (!hitPoint) fail('OBSCURED', '元素被遮挡或不在可点击区域');
    }

    const consumeSnapshot = () => {
      if (!reuseSnapshot) delete window.__lumaRead;
      else if (window.__lumaRead) window.__lumaRead.url = location.href;
    };

    if (request.action === 'check' || request.action === 'uncheck') {
      const ctrl = wrappedCtrl || e;
      const want = request.action === 'check';
      const current = (ctrl.tagName === 'INPUT')
        ? Boolean(ctrl.checked)
        : ctrl.getAttribute('aria-checked')?.trim().toLowerCase() === 'true';
      consumeSnapshot();
      if (current === want) {
        return {ok: true, dispatched: false, changed: false, checked: current};
      }
      dispatched = true;
      dispatchRealisticClick(clickHost);
      const after = (ctrl.tagName === 'INPUT')
        ? Boolean(ctrl.checked)
        : ctrl.getAttribute('aria-checked')?.trim().toLowerCase() === 'true';
      return {ok: true, dispatched: true, changed: after !== current, checked: after};
    }

    consumeSnapshot();
    dispatched = true;

    if (request.action === 'hover') {
      dispatchRealisticHover(clickHost, coordPoint?.x, coordPoint?.y);
      return {ok: true, dispatched: true, tag: e.tagName.toLowerCase()};
    }

    if (request.action === 'press') {
      const spec = resolveKeySpec(request.key);
      if (typeof e.focus === 'function') e.focus({preventScroll: true});
      dispatchKeyPress(e, spec);
      if (request.submit && spec.key === 'Enter') triggerSubmit(e);
      return {ok: true, dispatched: true, key: spec.key, tag: e.tagName.toLowerCase()};
    }

    if (request.action === 'click') {
      const tapCss = coordinateMode
        ? {topX: Number(request.x), topY: Number(request.y), localX: coordPoint.x, localY: coordPoint.y}
        : hitPoint;
      if (request.nativeTap && tapCss) {
        const vv = window.visualViewport;
        const vw = (vv && vv.width > 0) ? vv.width : (window.innerWidth || 0);
        const vh = (vv && vv.height > 0) ? vv.height : (window.innerHeight || 0);
        const ox = vv ? (vv.offsetLeft || 0) : 0;
        const oy = vv ? (vv.offsetTop || 0) : 0;
        const normX = vw > 0 ? (tapCss.topX - ox) / vw : -1;
        const normY = vh > 0 ? (tapCss.topY - oy) / vh : -1;
        if (normX >= 0 && normX <= 1 && normY >= 0 && normY <= 1) {
          if (window.__lumaPendingTap?.cleanup) {
            try { window.__lumaPendingTap.cleanup(); } catch (_) {}
          }
          const token = String(Date.now()) + '-' + Math.random().toString(36).slice(2);
          const targetWin = e.ownerDocument.defaultView || window;
          const state = {
            token,
            clickHost,
            touchDelivered: false,
            clickSuppressed: false,
            clickDelivered: false,
            submitted: false,
            fallbackClick: () => {
              dispatchRealisticClick(clickHost, tapCss.localX, tapCss.localY);
              if (request.submit && e.isConnected && !state.submitted) {
                state.submitted = true;
                triggerSubmit(e);
              }
            },
            cleanup: () => {},
          };
          const onTouchStartCapture = ev => {
            if (!ev.isTrusted) return;
            Promise.resolve().then(() => {
              if (ev.defaultPrevented) state.clickSuppressed = true;
            });
          };
          const onReleaseCapture = ev => {
            if (!ev.isTrusted) return;
            state.touchDelivered = true;
            Promise.resolve().then(() => {
              if (ev.defaultPrevented) {
                state.clickSuppressed = true;
                if (request.submit && e.isConnected && !state.submitted) {
                  state.submitted = true;
                  triggerSubmit(e);
                }
              }
            });
          };
          const onClickCapture = ev => {
            if (!ev.isTrusted) return;
            state.clickDelivered = true;
            if (request.submit) {
              Promise.resolve().then(() => {
                if (e.isConnected && !state.submitted) {
                  state.submitted = true;
                  triggerSubmit(e);
                }
              });
            }
          };
          targetWin.addEventListener('touchstart', onTouchStartCapture, true);
          targetWin.addEventListener('pointerup', onReleaseCapture, true);
          targetWin.addEventListener('touchend', onReleaseCapture, true);
          targetWin.addEventListener('click', onClickCapture, true);
          state.cleanup = () => {
            targetWin.removeEventListener('touchstart', onTouchStartCapture, true);
            targetWin.removeEventListener('pointerup', onReleaseCapture, true);
            targetWin.removeEventListener('touchend', onReleaseCapture, true);
            targetWin.removeEventListener('click', onClickCapture, true);
          };
          window.__lumaPendingTap = state;
          return {
            ok: true,
            dispatched: true,
            tag: e.tagName.toLowerCase(),
            tap: {token, normX, normY, cssX: tapCss.topX, cssY: tapCss.topY},
          };
        }
      }
      dispatchRealisticClick(clickHost, tapCss?.localX, tapCss?.localY);
      if (request.submit) triggerSubmit(e);
      return {ok: true, dispatched: true, tag: e.tagName.toLowerCase()};
    }

    if (request.action === 'select') {
      const selNode = wrappedCtrl?.tagName === 'SELECT' ? wrappedCtrl : e;
      const desired = String(request.text ?? request.value ?? '').trim();
      if (selNode.tagName === 'SELECT') {
        const opts = Array.from(selNode.options || []);
        const match = opts.find(o => o.value === desired || cleanSpace(o.text) === desired)
          || opts.find(o => o.value.toLowerCase() === desired.toLowerCase() || cleanSpace(o.text).toLowerCase() === desired.toLowerCase());
        if (!match) fail('OPTION_NOT_FOUND', `未找到下拉选项：${desired}`);
        selNode.focus();
        setTextFieldValue(selNode, match.value);
        return {ok: true, dispatched: true, value: match.value, text: cleanSpace(match.text)};
      }
      const optionNodes = Array.from(selNode.ownerDocument.querySelectorAll('[role="option"]')).filter(o => shown(o));
      const match = optionNodes.find(o => cleanSpace(o.innerText) === desired || o.getAttribute('data-value') === desired)
        || optionNodes.find(o => cleanSpace(o.innerText).toLowerCase().includes(desired.toLowerCase()));
      if (!match) fail('OPTION_NOT_FOUND', `未找到下拉选项：${desired}`);
      dispatchRealisticClick(match);
      return {ok: true, dispatched: true, text: cleanSpace(match.innerText)};
    }

    e.focus();
    const incoming = String(request.text ?? '');
    const shouldClear = request.action === 'fill' ? request.clear !== false : Boolean(request.clear);
    if (e.isContentEditable) {
      const next = shouldClear ? incoming : (String(e.innerText ?? '') + incoming);
      e.textContent = next;
      const EventClass = e.ownerDocument.defaultView.Event;
      e.dispatchEvent(new EventClass('input', {bubbles:true}));
      e.dispatchEvent(new EventClass('change', {bubbles:true}));
    } else {
      const next = shouldClear ? incoming : (String(e.value ?? '') + incoming);
      setTextFieldValue(e, next);
    }
    if (request.submit) triggerSubmit(e);
    return {ok: true, dispatched: true};
  } catch (error) {
    return {ok:false, code:typeof error.code === 'string' ? error.code : 'PAGE_ERROR', error:String(error.message || error), dispatched};
  }
}
