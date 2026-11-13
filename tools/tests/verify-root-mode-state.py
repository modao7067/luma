#!/usr/bin/env python3
import importlib.util
import mmap
import os
from pathlib import Path
import struct
import tempfile
import zlib
REPO=Path(__file__).resolve().parents[2]
spec=importlib.util.spec_from_file_location('mode_state',REPO/'backend/root/mode_state.py')
module=importlib.util.module_from_spec(spec);spec.loader.exec_module(module)
with tempfile.TemporaryFile() as source:
    source.truncate(module.SIZE)
    state=module.ModeState(os.dup(source.fileno()),os.getuid(),[80,24])
    state.values[70]=1;state.save()
    for part in [b'\033[?10',b'49h\033[?1;1000;1006;2004h\033[?25l']:
        state.feed(part)
    assert state.values[5]==1 and state.values[3]&(1|64|512|1024)==1|64|512|1024 and not state.values[3]&16
    state.feed(b'\033[2;20r\033[4h\033[5 q')
    assert state.values[8:10]==[1,20] and state.values[6]==1 and state.values[7]==2
    sequence=state.sequence;state.feed(b'ordinary output '*100000);assert state.sequence==sequence
    state.feed(b'\033]1337;File=inline=1:FAKE\033[?1049l\007')
    assert state.values[5]==1,'OSC payload changed screen mode'
    state.feed(b'\033PqFAKE\033[?1049l\033\\')
    assert state.values[5]==1,'DCS payload changed screen mode'
    state.feed(b'\033[?1049l\033[?1;1000;1006;2004l\033[?25h\033]133;D;0\007')
    assert state.values[5]==0 and state.values[3]&16 and not state.values[3]&(1|64|512|1024) and state.values[70]==2
    print('PASS split/offline alternate-screen, input/cursor modes, margins, command completion; OSC/DCS payloads do not change modes')
    print('PASS literal offline output does not write mode checkpoints or retain text')
    state.feed(b'\033[?1016h\033[?1016s')
    assert state.values[3]&16384 and state.values[4]&16384,'1016 current/saved not both set'
    state.feed(b'\033[?1016l')
    assert not state.values[3]&16384 and state.values[4]&16384,'1016 disable rewrote its saved value'
    state.feed(b'\033[?1016r')
    assert state.values[3]&16384,'1016 saved restore did not return the mode'
    state.feed(b'\033[?1016l\033[?1016s')
    assert not state.values[3]&16384 and not state.values[4]&16384,'1016 save of disabled current state was not recorded'
    print('PASS 1016 current/saved bits in the existing 80-word checkpoint')
    previous=list(state.values);previous_serial=state.sequence
    state.feed(b'\033[?1049h')
    offset=(state.sequence&1)*module.SLOT
    state.memory[offset+16]^=1
    state.load();assert state.sequence==previous_serial and state.values==previous
    print('PASS checksummed two-slot fallback after a corrupt/torn newest checkpoint')
    state.close()
    read_fd,write_fd=os.pipe()
    try:
        try:module.ModeState(os.dup(read_fd),os.getuid(),[80,24])
        except ValueError:pass
        else:raise AssertionError('Non-regular metadata FD accepted')
    finally:os.close(read_fd);os.close(write_fd)
    print('PASS invalid metadata descriptors rejected')

def record(values,serial):
    body=struct.pack('>'+str(module.WORDS)+'i',*values)
    return struct.pack('>QII',serial,module.MAGIC,module.WORDS)+body+struct.pack('>I',zlib.crc32(body))

invalid={1:0,2:65536,3:-1,4:32768,5:2,6:-1,7:3,8:-1,9:25,10:80,11:81,
         12:2,15:2,16:-1,17:65536,18:259,19:-0x1000001,20:512,21:32768,
         22:2,25:-1,30:-1,33:2,34:5,35:32,37:2,38:-2,69:100000,
         70:4,71:-1,72:1000000,74:80,75:24}
for field,bad in invalid.items():
    with tempfile.TemporaryFile() as source:
        source.truncate(module.SIZE)
        good=module.defaults(80,24);good[3]|=4096;good[70]=2
        broken=list(good);broken[field]=bad
        source.seek(0);source.write(record(good,2))
        source.seek(module.SLOT);source.write(record(broken,3));source.flush()
        state=module.ModeState(os.dup(source.fileno()),os.getuid(),[80,24])
        if state.sequence!=2 or state.values!=good:raise AssertionError('Accepted invalid field '+str(field))
        state.feed(b'\033[1m');state.close()
print('PASS '+str(len(invalid))+' CRC-valid out-of-range fields reject newest slot and retain valid fallback')
with tempfile.TemporaryFile() as source:
    source.truncate(module.SIZE)
    bad=module.defaults(80,24);bad[34]=6;bad[35]=2147483647
    source.write(record(bad,9));source.flush()
    state=module.ModeState(os.dup(source.fileno()),os.getuid(),[80,24])
    if state.sequence!=0 or state.values!=module.defaults(80,24):raise AssertionError('Bad-only record did not fall back to defaults')
    state.feed(b'1m');state.close()
print('PASS bad-only continuation checkpoint loads defaults and feed remains usable')
legal=module.defaults(80,24)
legal[16:25]=[600,900,-0xff0000,-1,511,32767,1,1,0]
legal[3]=legal[4]=32767;legal[35]=31;legal[36]=-1;legal[38:70]=[65535]*32
legal[76]=struct.unpack('>i',bytes([0x82,0xf0,0x9f,0]))[0]
if not module.valid_checkpoint(legal):raise AssertionError('Legal legacy bits/RGB/resized saved cursor/UTF8 extension rejected')
legal[34]=10;legal[76]=15<<24
if not module.valid_checkpoint(legal):raise AssertionError('Legal 15-byte OSC prefix rejected')
legal[76]=16<<24
if module.valid_checkpoint(legal):raise AssertionError('Oversized OSC prefix accepted')
print('PASS legacy masks/protected SGR, RGB, saved cursors before shrink, 32 args and native UTF8/OSC continuations remain legal')
