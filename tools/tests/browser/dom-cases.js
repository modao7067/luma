async function() {
  const checks = [];
  const api = window.lumaApi;
  const check = (name, pass, detail) => { if (!pass) throw Error(name + ': ' + JSON.stringify(detail)); checks.push(name); };
  const reset = html => { document.body.innerHTML = html; delete window.__lumaRead; };
  const read = () => { const r = api({action:'read'}); check('read returns current snapshot', r.ok, r); return r; };
  const action = (r, target, kind = 'click', extra = {}) => api({action:kind, snapshot:r.snapshot, ...target, ...extra});
  const rejected = (name, result, code) => check(name, !result.ok && !result.dispatched && (!code || result.code === code), result);
  reset('<button id="first">First</button><input id="field" placeholder="Name"><input type="password" value="SECRET_VALUE"><button style="display:none">Hidden</button><button disabled>Disabled</button>');
  let r = read(), field = r.elements.find(e => e.id === 'field');
  check('bounded visible output never returns password value', r.elements.length === 4 && !JSON.stringify(r).includes('SECRET_VALUE'), r);
  document.body.insertAdjacentHTML('afterbegin', '<button>Inserted</button>');
  let result = action(r, {index:field.index}, 'fill', {text:'Ada'});
  check('legacy index preserves identity after insertion', result.ok && document.querySelector('#field').value === 'Ada', result);
  rejected('snapshot dispatch cannot be replayed', action(r, {index:0}), 'STALE_SNAPSHOT');
  r = read(); const old = r.elements.find(e => e.id === 'first');
  document.querySelector('#first').outerHTML = '<button id="first">Replacement</button>';
  rejected('same-selector replacement is not retargeted', action(r, {selector:'#first'}), 'STALE_NODE');
  rejected('old ref to detached node is rejected', action(r, {ref:old.ref}), 'STALE_NODE');
  rejected('old index to detached node is rejected', action(r, {index:old.index}), 'STALE_NODE');
  r = read(); document.body.insertAdjacentHTML('beforeend', '<button id="new">New</button>');
  rejected('selector cannot authorize a node inserted after read', action(r, {selector:'#new'}), 'STALE_NODE');
  rejected('missing snapshot rejected', api({action:'click', selector:'#field'}), 'STALE_SNAPSHOT');
  rejected('arbitrary JavaScript endpoint remains absent', api({action:'evaluate', text:'alert(1)'}), 'UNKNOWN_ACTION');

  reset(Array.from({length:310}, (_, i) => '<button>' + i + '</button>').join('') + '<div id="ordinary" onclick="this.dataset.clicked=\'yes\'">Plain div</div>');
  r = read();
  check('interactive output cap is explicit', r.elements.length === 300 && r.elementsTruncated, r.elements.length);
  result = action(r, {selector:'#ordinary'});
  check('legacy CSS beyond 300 items and ordinary elements still works', result.ok && document.querySelector('#ordinary').dataset.clicked === 'yes', result);
  reset('<button class="duplicate">One</button><button class="duplicate" hidden>Two</button>');
  r = read(); rejected('ambiguous selector rejects even hidden duplicates', action(r, {selector:'.duplicate'}), 'AMBIGUOUS_TARGET');
  rejected('invalid CSS fails explicitly', action(r, {selector:'['}), 'INVALID_SELECTOR');

  reset('<button id="target">Target</button><button id="opacity" style="opacity:0">Zero opacity</button><button id="hidden" hidden>Hidden</button><fieldset disabled><input id="disabled"></fieldset><div aria-disabled="true"><button id="aria">Aria disabled</button></div>');
  r = read();
  rejected('hidden target is not actionable', action(r, {selector:'#hidden'}), 'NOT_VISIBLE');
  rejected('opacity-zero target is not actionable', action(r, {selector:'#opacity'}), 'NOT_VISIBLE');
  rejected('fieldset disabled is inherited', action(r, {selector:'#disabled'}), 'DISABLED');
  rejected('aria disabled ancestor is honored', action(r, {selector:'#aria'}), 'DISABLED');
  document.body.insertAdjacentHTML('beforeend', '<div id="cover" style="position:fixed;inset:0;z-index:9999;background:white"></div>');
  rejected('occluded target is not clicked', action(r, {selector:'#target'}), 'OBSCURED');
  document.querySelector('#cover').remove();
  check('pre-dispatch failure does not consume snapshot', action(r, {selector:'#target'}).ok);

  reset('<fieldset disabled><legend><input id="legend-field"></legend><a id="fieldset-link" href="#help">Help</a><input id="really-disabled"></fieldset><div style="visibility:hidden"><button id="shown-child" style="visibility:visible" onclick="this.dataset.clicked=1">Shown child</button></div>');
  r = read();
  check('fieldset first legend input is enabled in read', r.elements.find(e => e.id === 'legend-field')?.disabled === false, r);
  check('fieldset ordinary link is enabled in read', r.elements.find(e => e.id === 'fieldset-link')?.disabled === false, r);
  check('fieldset ordinary input remains disabled', r.elements.find(e => e.id === 'really-disabled')?.disabled === true, r);
  check('visibility-visible child overrides hidden ancestor in read and wait', r.elements.some(e => e.id === 'shown-child') && api({action:'wait',selector:'#shown-child',state:'visible'}).matched, r);
  check('first legend field can be filled', action(r, {selector:'#legend-field'}, 'fill', {text:'legend value'}).ok && document.querySelector('#legend-field').value === 'legend value');
  r = read();
  check('ordinary fieldset link can be clicked', action(r, {selector:'#fieldset-link'}).ok);
  history.replaceState({}, '', location.pathname);
  r = read();
  check('visibility-visible child can be clicked', action(r, {selector:'#shown-child'}).ok && document.querySelector('#shown-child').dataset.clicked === '1');

  reset('<input id="edit"><button id="send" onclick="window.clicks=(window.clicks||0)+1">Send</button>');
  window.events = []; document.querySelector('#edit').addEventListener('input', () => window.events.push('input'));
  document.querySelector('#edit').addEventListener('change', () => window.events.push('change'));
  r = read(); result = action(r, {ref:r.elements.find(e => e.id === 'edit').ref}, 'fill', {text:'中文'});
  check('ref fill dispatches native setter and one input/change pair', result.ok && document.querySelector('#edit').value === '中文' && window.events.join(',') === 'input,change', result);
  r = read(); history.pushState({}, '', '#changed');
  rejected('URL change invalidates old snapshot', action(r, {selector:'#send'}), 'STALE_SNAPSHOT');
  history.replaceState({}, '', location.pathname);

  reset('<div id="host"></div>');
  const shadow = document.querySelector('#host').attachShadow({mode:'open'});
  shadow.innerHTML = '<input id="shadow-field"><button id="shadow-click" onclick="this.dataset.clicked=\'yes\'">Shadow button</button>';
  r = read(); check('open shadow input is listed', r.elements.some(e => e.id === 'shadow-field'), r);
  result = action(r, {selector:'#shadow-field'}, 'fill', {text:'shadow value'});
  check('selector resolves open shadow node', result.ok && shadow.querySelector('input').value === 'shadow value', result);
  r = read(); result = action(r, {ref:r.elements.find(e => e.id === 'shadow-click').ref});
  check('hit testing pierces open shadow roots', result.ok && shadow.querySelector('button').dataset.clicked === 'yes', result);

  reset('<iframe id="same" style="width:500px;height:180px;border:3px solid black"></iframe><iframe id="opaque" sandbox="allow-scripts" srcdoc="<button>Cross origin</button>"></iframe>');
  const frame = document.querySelector('#same');
  await new Promise(resolve => { frame.onload = resolve; frame.srcdoc = '<input id="frame-field"><button id="frame-button" onclick="this.dataset.clicked=\'yes\'">Frame button</button>'; });
  r = read(); check('same-origin iframe elements carry frame depth', r.elements.find(e => e.id === 'frame-field')?.frameDepth === 1, r);
  check('cross-origin frames are explicitly unavailable', r.inaccessibleFrames >= 1, r);
  result = action(r, {selector:'#frame-field'}, 'fill', {text:'frame value'});
  check('same-origin frame fill uses owning document', result.ok && frame.contentDocument.querySelector('input').value === 'frame value', result);
  r = read(); const frameRef = r.elements.find(e => e.id === 'frame-button').ref;
  await new Promise(resolve => { frame.onload = resolve; frame.srcdoc = '<button id="frame-button">New document</button>'; });
  rejected('iframe navigation invalidates old document refs', action(r, {ref:frameRef}), 'STALE_DOCUMENT');

  reset('<button id="late" hidden disabled>Later</button>');
  const probe = state => api({action:'wait', selector:'#late', state});
  check('hidden wait succeeds without mutation', probe('hidden').matched);
  check('visible wait initially pending', !probe('visible').matched);
  setTimeout(() => { document.querySelector('#late').hidden = false; document.querySelector('#late').disabled = false; }, 50);
  const end = Date.now() + 1000;
  while (!probe('actionable').matched && Date.now() < end) await new Promise(resolve => setTimeout(resolve, 20));
  check('dynamic condition becomes actionable with observational probes', probe('actionable').matched);
  check('detached selector condition succeeds', api({action:'wait', selector:'#absent', state:'detached'}).matched);
  document.body.insertAdjacentHTML('beforeend', '<button id="late">Duplicate</button>');
  rejected('wait does not hide ambiguity as pending', probe('visible'), 'AMBIGUOUS_TARGET');

  reset(
    '<h1>Guide Title</h1><ul><li>Item Alpha</li></ul>' +
    '<button id="dedup-btn"><span style="cursor:pointer">Inner Text</span></button>' +
    '<label id="custom-switch"><input id="hidden-cb" type="checkbox" style="opacity:0;width:1px;height:1px">Agree Terms</label>' +
    '<select id="plan"><option value="free">Free Tier</option><option value="pro">Pro Plan</option></select>' +
    '<div id="rich" contenteditable="true">Initial note</div>' +
    '<input id="multi-a"><input id="multi-b">'
  );
  r = read();
  check('markdown extracts headings and list items', r.markdown.includes('# Guide Title') && r.markdown.includes('- Item Alpha'), r.markdown);
  check('nested pointer span inside button is deduplicated', r.elements.filter(e => e.text.includes('Inner Text')).length === 1, r.elements);
  const switchRef = r.elements.find(e => e.text.includes('Agree Terms'))?.ref;
  check('visible label wrapping opacity-0 checkbox is exposed', Boolean(switchRef), r.elements);
  let checkRes1 = api({action:'check', snapshot:'__live__', ref:switchRef});
  let checkRes2 = api({action:'check', snapshot:'__live__', ref:switchRef});
  check('check is idempotent on hidden-input label wrapper', checkRes1.ok && checkRes1.checked === true && checkRes2.ok && checkRes2.dispatched === false && document.querySelector('#hidden-cb').checked === true, {checkRes1, checkRes2});
  let uncheckRes = api({action:'uncheck', snapshot:'__live__', ref:switchRef});
  check('uncheck clears checkbox state cleanly', uncheckRes.ok && uncheckRes.checked === false && document.querySelector('#hidden-cb').checked === false, uncheckRes);

  const planEl = r.elements.find(e => e.id === 'plan');
  check('select element exposes option metadata', planEl?.options?.length === 2 && planEl.options[1].text === 'Pro Plan', planEl);
  let selRes = api({action:'select', snapshot:'__live__', ref:planEl.ref, text:'Pro Plan'});
  check('select action chooses option by visible label', selRes.ok && document.querySelector('#plan').value === 'pro', selRes);

  const richEl = r.elements.find(e => e.id === 'rich');
  let typeRes = api({action:'type', snapshot:'__live__', ref:richEl.ref, text:' + appended'});
  check('contenteditable supports type append', typeRes.ok && document.querySelector('#rich').textContent === 'Initial note + appended', typeRes);

  const refA = r.elements.find(e => e.id === 'multi-a').ref;
  const refB = r.elements.find(e => e.id === 'multi-b').ref;
  let fillA = api({action:'fill', snapshot:r.snapshot, reuseSnapshot:true, ref:refA, text:'FirstVal'});
  let fillB = api({action:'fill', snapshot:r.snapshot, reuseSnapshot:true, ref:refB, text:'SecondVal'});
  check('reuseSnapshot allows filling multiple fields from one read', fillA.ok && fillB.ok && document.querySelector('#multi-a').value === 'FirstVal' && document.querySelector('#multi-b').value === 'SecondVal', {fillA, fillB});

  const btnBounds = r.elements.find(e => e.id === 'dedup-btn').bounds;
  document.querySelector('#dedup-btn').onclick = () => { window.coordClicked = true; };
  let coordRes = api({action:'click', x:btnBounds.x + 5, y:btnBounds.y + 5});
  check('viewport coordinate click hits target element', coordRes.ok && window.coordClicked === true, coordRes);
  let scrollRes = api({action:'scroll', direction:'down', amount:120});
  check('scroll action returns viewport scroll metrics', scrollRes.ok && typeof scrollRes.scrollY === 'number', scrollRes);

  reset(
    '<div id="outside"><a id="safe-link" href="https://example.com/docs">Docs</a><a id="js-link" href="javascript:alert(1)">Bad</a><p>Outside body</p></div>' +
    '<div id="modal"><h2>Dialog Title</h2>' +
    '<label for="mail-in">Email</label><input id="mail-in" placeholder="Search...">' +
    '<button data-testid="save-btn" onclick="this.dataset.saved=\'1\'">Save Changes</button>' +
    '<button onclick="this.dataset.dup=\'1\'">Copy</button><button onclick="this.dataset.dup=\'2\'">Copy</button>' +
    '<div id="hover-box" style="width:60px;height:30px" onmouseenter="this.dataset.hovered=\'1\'">Hover me</div>' +
    '</div>'
  );
  let lean = api({action:'read', interactiveOnly:true, includeUrls:true});
  check('interactiveOnly omits markdown/text and includeUrls keeps safe href only',
    lean.ok && lean.markdown === '' && lean.text === '' &&
    lean.elements.find(e => e.id === 'safe-link')?.href === 'https://example.com/docs' &&
    !lean.elements.find(e => e.id === 'js-link')?.href, lean);
  let scoped = api({action:'read', scopeSelector:'#modal'});
  check('scopeSelector isolates elements and markdown to container',
    scoped.ok && !scoped.markdown.includes('Outside body') && scoped.markdown.includes('## Dialog Title') &&
    scoped.elements.every(e => e.id !== 'safe-link'), scoped);
  check('semantic label fill works', api({action:'fill', snapshot:'__live__', selector:'label=Email', text:'a@b.com'}).ok && document.querySelector('#mail-in').value === 'a@b.com');
  check('semantic placeholder fill works', api({action:'fill', snapshot:'__live__', selector:'placeholder=Search...', text:'next'}).ok && document.querySelector('#mail-in').value === 'next');
  check('semantic testid click works', api({action:'click', snapshot:'__live__', selector:'testid=save-btn'}).ok && document.querySelector('[data-testid="save-btn"]').dataset.saved === '1');
  check('semantic role+name click works', api({action:'click', snapshot:'__live__', selector:'role=button:"Save Changes"'}).ok);
  rejected('duplicate semantic text fails with AMBIGUOUS_TARGET', api({action:'click', snapshot:'__live__', selector:'text="Copy"'}), 'AMBIGUOUS_TARGET');
  check('hover dispatches mouseenter', api({action:'hover', snapshot:'__live__', selector:'#hover-box'}).ok && document.querySelector('#hover-box').dataset.hovered === '1');
  window.lastKey = '';
  document.addEventListener('keydown', ev => { window.lastKey = ev.key; }, {once: true});
  let pressRes = api({action:'press', snapshot:'__live__', key:'Escape'});
  check('targetless press dispatches Escape keydown', pressRes.ok && pressRes.key === 'Escape' && window.lastKey === 'Escape', pressRes);
  const range = document.createRange();
  range.selectNodeContents(document.querySelector('#modal h2'));
  const sel = window.getSelection();
  sel.removeAllRanges();
  sel.addRange(range);
  let selOut = api({action:'selection'});
  check('selection extracts highlighted page text', selOut.ok && selOut.selection === 'Dialog Title', selOut);
  sel.removeAllRanges();

  reset('<div id="player-tri" role="button" style="width:65px;height:65px">Play</div>');
  const tri = document.querySelector('#player-tri');
  let pointerEvents = 0, clickCount = 0;
  tri.addEventListener('pointerup', ev => { if (ev.clientX > 0 && ev.clientY > 0) pointerEvents++; });
  tri.addEventListener('click', ev => { if (ev.clientX > 0 && ev.clientY > 0) clickCount++; });
  let triRead = read();
  let triRef = triRead.elements.find(e => e.id === 'player-tri').ref;
  let triClick = action(triRead, {ref: triRef});
  check('ref click dispatches realistic pointerup and positioned click on custom div',
    triClick.ok && pointerEvents === 1 && clickCount === 1, {triClick, pointerEvents, clickCount});

  pointerEvents = 0; clickCount = 0;
  let nativePrep = api({action:'click', snapshot:'__live__', selector:'#player-tri', nativeTap:true});
  check('nativeTap defers synthetic click and returns normalized viewport point',
    nativePrep.ok && nativePrep.tap && nativePrep.tap.normX > 0 && nativePrep.tap.normX < 1 &&
    nativePrep.tap.normY > 0 && nativePrep.tap.normY < 1 && pointerEvents === 0 && clickCount === 0,
    nativePrep);
  let pendingCheck = api({action:'__finalizeTap', token:nativePrep.tap.token, fallback:false});
  let fallbackCheck = api({action:'__finalizeTap', token:nativePrep.tap.token, fallback:true});
  check('finalizeTap falls back cleanly once when native touch is absent',
    pendingCheck.status === 'pending' && fallbackCheck.status === 'fallback' && pointerEvents === 1 && clickCount === 1,
    {pendingCheck, fallbackCheck, pointerEvents, clickCount});

  return {passed:checks.length, checks};
}
