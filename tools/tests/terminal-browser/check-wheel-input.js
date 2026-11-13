#!/usr/bin/env node
'use strict';
const fs = require('fs');

const bundle = process.argv[2];
if (!bundle) {
  console.error('usage: check-wheel-input.js <browser/dist/main.js>');
  process.exit(2);
}
const code = fs.readFileSync(bundle, 'utf8');

const anchor = 'wheel(event) {\n        this.syncFocus();';
const start = code.indexOf(anchor);
if (start < 0) {
  console.error('FAIL PageInput.wheel with syncFocus not found');
  process.exit(1);
}
const brace = code.indexOf('{', start);
let depth = 0, end = -1;
for (let i = brace; i < code.length; i++) {
  const c = code[i];
  if (c === '{') depth++;
  else if (c === '}' && --depth === 0) { end = i + 1; break; }
}
if (end < 0) {
  console.error('FAIL unbalanced wheel function');
  process.exit(1);
}
const body = code.slice(brace + 1, end - 1);
const wheel = new Function('wholeDelta', 'return function wheel(event) {' + body + '};')((v) => Math.trunc(v));

let checks = 0;
function check(ok, message) {
  if (!ok) throw new Error(message);
  checks++;
  console.log('PASS ' + message);
}
function stub(scale) {
  return {
    scale,
    lastX: 0, lastY: 0,
    wheelRemainderX: 0, wheelRemainderY: 0,
    ticks: 0, pinches: 0, sent: null, tickX: null, tickY: null,
    target: { scale() { return this._scale; }, _scale: scale },
    syncFocus() {},
    wheelTick(event) { this.ticks++; this.tickX = this.lastX; this.tickY = this.lastY; },
    pinch(event) { this.pinches++; },
    pointerModifiers() { return 0; },
    send(payload) { this.sent = payload; }
  };
}

const tick = stub(2);
tick.target._scale = 2;
wheel.call(tick, { x: 101, y: 53, mods: { ctrl: false }, precise: false, deltaX: 0, deltaY: -1 });
check(tick.ticks === 1 && tick.pinches === 0, 'coarse wheel keeps the tick path');
check(tick.lastX === 51 && tick.lastY === 27, 'coarse wheel updates lastX/lastY with page scale before dispatch');
check(tick.tickX === 51 && tick.tickY === 27, 'wheelTick observes the wheel event position, not a stale pointer');

const precise = stub(2);
wheel.call(precise, { x: 99, y: 51, mods: { ctrl: false }, precise: true, deltaX: 0, deltaY: -80 });
check(precise.sent && precise.sent.x === 50 && precise.sent.y === 26,
  'precise wheel sends the event position with scale applied');
check(precise.sent.deltaY === 40 && precise.wheelRemainderY === 0,
  'precise wheel keeps the existing scale/remainder conversion');

const pinch = stub(2);
wheel.call(pinch, { x: 40, y: 33, mods: { ctrl: true }, precise: true, deltaX: 0, deltaY: 0 });
check(pinch.pinches === 1 && pinch.sent === null, 'ctrl+precise keeps the pinch path');
check(pinch.lastX === 20 && pinch.lastY === 17, 'pinch also updates the pointer before dispatch');

console.log('WHEEL INPUT OK checks=' + checks);
