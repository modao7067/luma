#!/usr/bin/env python3
import fcntl
import struct
import os
import select
import signal
import sys
import termios
import time
import tty
saved=termios.tcgetattr(0)
def emit(data):os.write(1,data)
resize_only=os.environ.get('LUMA_TEST_RESIZE_ONLY')=='1'
last_size=fcntl.ioctl(0,termios.TIOCGWINSZ,b'\0'*8)
def redraw(*_):
    global last_size
    size=fcntl.ioctl(0,termios.TIOCGWINSZ,b'\0'*8)
    if resize_only:
        if size==last_size:emit(b'\033[6;1Hanimated dot');return
        last_size=size
        rows=struct.unpack('HHHH',size)[0]
        emit(b'\033[2J\033[HSTATIC_REPAINT:'+str(rows).encode()+b'\r\n');return
    emit(b'\033[2J\033[HTUI_REDRAW_ONLY\r\n')
signal.signal(signal.SIGWINCH,redraw)
try:
    tty.setraw(0)
    emit(b'\033[?1049h\033[?1h\033[?1000h\033[?1006h\033[?2004h\033[?25l\033[2J\033[HTUI_READY\r\n')
    while True:
        data=os.read(0,64)
        if b'R' in data:emit(b'\033[HTUI_RESTORED:'+os.environ.get('KEEP_SERVICE','').encode()+b'\r\n')
        if b'E' in data:emit(b'\r\nTUI_EXIT_PENDING\r\n');time.sleep(.5);break
finally:
    emit(b'\033[?1049l\033[?1l\033[?1000l\033[?1006l\033[?2004l\033[?25h')
    termios.tcsetattr(0,termios.TCSANOW,saved)
