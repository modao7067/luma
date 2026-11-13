#!/usr/bin/env python3
import importlib.util
import os
import struct
import tempfile
import zlib
from pathlib import Path

REPO = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('mode_state', REPO / 'backend/root/mode_state.py')
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

def open_state(folder, size=(80, 24)):
    folder.mkdir(parents=True, exist_ok=True)
    path = folder / 'test.modes'
    if not path.exists():
        path.write_bytes(b'\0' * module.SIZE)
    fd = os.open(path, os.O_RDWR)
    return module.ModeState(fd, os.getuid(), list(size)), path

def reopen(state, path, size=(80, 24)):
    state.close()
    return module.ModeState(os.open(path, os.O_RDWR), os.getuid(), list(size))

def v1_record(values, serial):
    body = struct.pack('>' + str(module.WORDS) + 'i', *values)
    return struct.pack('>QII', serial, module.MAGIC, module.WORDS) + body + struct.pack('>I', zlib.crc32(body))

def write_v1(slot, values, serial):
    return [(slot * module.SLOT, v1_record(values, serial))]

def check(condition, message):
    assert condition, message
    print('PASS ' + message)

with tempfile.TemporaryDirectory(prefix='luma-kb-state-') as directory:
    folder = Path(directory)

    state, path = open_state(folder)
    check(state.keyboard[1] == 54, 'fresh scanner uses 1036/1035 current+saved defaults')
    state.feed(b'\033[?1049h\033[>1u\033[>3u\033[=4;2u')
    check(state.keyboard[11] == 2 and state.keyboard[13] == 1 and state.keyboard[14] == 7,
          'offline kitty push/or tracked on the alternate screen')
    state.feed(b'\033[<1u')
    check(state.keyboard[11] == 1 and state.keyboard[13] == 1 and state.keyboard[14] == 0,
          'offline pop clears only the popped layer')
    state.feed(b'\033[>4;2m\033[?1036s\033[?1036l\033[?1035l\033[?67h')
    check(state.keyboard[1] & 1 and not state.keyboard[1] & (1 << 1)
          and not state.keyboard[1] & (1 << 2) and state.keyboard[1] & (1 << 3)
          and state.keyboard[1] & (1 << 4),
          'modifyOtherKeys and extension current/saved bits tracked')

    state.kb_confident = True
    state.feed(b'\033[>16u')
    state.close()
    reopened, _ = open_state(folder)
    check(reopened.keyboard == state.keyboard and reopened.kb_available and reopened.kb_confident,
          'bound annex round-trips through a reopened mmap')

    raw = path.read_bytes()
    slot = 0 if struct.unpack_from('>Q', raw, 0)[0] % 2 == 0 else 1
    serial, magic, count = struct.unpack_from('>QII', raw, slot * module.SLOT)
    body = raw[slot * module.SLOT + 16:slot * module.SLOT + 16 + module.WORDS * 4]
    check(serial > 0 and magic == module.MAGIC and count == 80 and len(body) == 320,
          'old 80-word reader sees an unchanged v1 header/body')
    check(struct.unpack_from('>i', body, 0)[0] == 1, 'old reader body version is 1')

    state, path = open_state(folder / 'ris')
    state.feed(b'\033[?1049h\033[>1u\033[>4;2m\033[?1036l\033c')
    check(state.values[5] == 0 and state.keyboard[1] == 54 and state.keyboard[2] == 0
          and state.keyboard[11] == 0,
          'RIS returns to primary and resets stacks/modify/ext defaults')

    state, path = open_state(folder / 'decstr')
    state.feed(b'\033[?1h\033[?66h\033[>1u\033[>4;2m\033[!p')
    check(state.keyboard[2] == 1 and state.keyboard[4] == 1 and not state.keyboard[1] & 1,
          'DECSTR keeps the kitty stack but clears modifyOtherKeys')
    check(not state.values[3] & 1 and not state.values[3] & 32 and state.values[7] == 0
          and state.values[6] == 0 and state.values[3] & 8 and state.values[3] & 16,
          'DECSTR resets tracked cursor/keypad/style/insert/wrap/visible modes')

    state, path = open_state(folder / 'partial')
    state.feed(b'\033[=5;2')
    check(state.keyboard[20] == ord('='), 'partial CSI = intermediate is persisted')
    state.feed(b'u')
    check(state.keyboard[3] == 5, 'partial set completes with set semantics')
    state, path = open_state(folder / 'partial-pop')
    state.feed(b'\033[>1u\033[<1')
    check(state.keyboard[20] == ord('<'), 'partial CSI < intermediate is persisted')
    state.feed(b'u')
    check(state.keyboard[2] == 0 and state.keyboard[3] == 0, 'partial pop completes with pop semantics')

    state, path = open_state(folder / 'orphan')
    state.kb_confident = True
    state.feed(b'\033[>7u')
    saved = list(state.values)
    saved[3] ^= 1
    serial = state.sequence + 1
    offset = (serial & 1) * module.SLOT
    state.memory[offset:offset + len(v1_record(saved, serial))] = v1_record(saved, serial)
    state.load()
    check(state.kb_available is False and state.kb_orphan is True,
          'old writer leaves an orphan annex that is not restored')

    state, path = open_state(folder / 'future')
    record = v1_record(module.defaults(80, 24), 1)
    state.memory[0:len(record)] = record
    future_kb = module.default_keyboard()
    future_kb[2] = 7; future_kb[3] = 31
    annex = bytearray(module.ANNEX_TOTAL)
    struct.pack_into('>IHHQII', annex, 0, module.ANNEX_MAGIC, module.ANNEX_VERSION,
                     module.ANNEX_TOTAL, 999, 0, 1)
    struct.pack_into('>' + str(module.KB_WORDS) + 'i', annex, module.ANNEX_HEADER, *future_kb)
    struct.pack_into('>I', annex, module.ANNEX_TOTAL - 4, zlib.crc32(annex[:module.ANNEX_TOTAL - 4]))
    state.memory[module.SLOT:module.SLOT + 8] = struct.pack('>Q', 0)
    state.memory[module.SLOT + module.ANNEX_OFFSET:
                 module.SLOT + module.ANNEX_OFFSET + module.ANNEX_TOTAL] = annex
    state.load()
    check(state.kb_available is False and state.kb_orphan is True,
          'unpublished serial-0 annex is never used for restore')

    state, path = open_state(folder / 'corrupt')
    state.feed(b'\033[?2004h\033[>1u')
    values = list(state.values)
    slot = (state.sequence & 1)
    state.memory[slot * module.SLOT + module.ANNEX_OFFSET + 40] ^= 0x55
    state.load()
    check(state.keyboard == module.default_keyboard() and state.values == values,
          'corrupt annex leaves the v1 record usable')

    state.close()

print('ROOT KEYBOARD STATE OK')
