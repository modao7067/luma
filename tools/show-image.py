#!/usr/bin/env python3
import argparse
import base64
from pathlib import Path
import sys
import re
import shutil

def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('image', type=Path)
    parser.add_argument('--width', default='100%', help='terminal cells, pixels (e.g. 320px), or percentage')
    parser.add_argument('--protocol', choices=['iterm2', 'kitty'], default='iterm2', help='kitty requires a PNG image and width in cells or percent')
    args = parser.parse_args()
    if not sys.stdout.isatty():
        parser.error('请在 Luma 的终端里执行，输出不要重定向')
    if not re.fullmatch(r'[1-9][0-9]*(?:px|%)?', args.width):
        parser.error('width 必须是正整数，可附 px 或 %')
    try:
        with args.image.open('rb') as source:
            data = source.read(6 * 1024 * 1024 + 1)
    except OSError as error:
        parser.error(str(error))
    if len(data) > 6 * 1024 * 1024:
        parser.error('图片文件超过 6 MiB，请先缩小图片')
    if not data:
        parser.error('图片为空')
    encoded = base64.b64encode(data)
    out = sys.stdout.buffer
    if args.protocol == 'kitty':
        if not data.startswith(b'\x89PNG\r\n\x1a\n'):
            parser.error('Kitty 模式请传入 PNG；JPEG 等图片需先由客户端转换为 PNG/RGB')
        if args.width.endswith('px'):
            parser.error('Kitty 模式的 width 请使用列数或百分比')
        columns = max(1, round(shutil.get_terminal_size().columns * int(args.width[:-1]) / 100)) if args.width.endswith('%') else int(args.width)
        if columns > 4096:
            parser.error('Kitty 模式最多显示 4096 列')
        for offset in range(0, len(encoded), 4096):
            controls = f'a=T,f=100,t=d,q=2,c={columns},' if offset == 0 else ''
            more = int(offset + 4096 < len(encoded))
            out.write(f'\x1b_G{controls}m={more};'.encode('ascii') + encoded[offset:offset + 4096] + b'\x1b\\')
        out.write(b'\r\n')
        out.flush()
        return
    out.write(f'\x1b]1337;MultipartFile=inline=1;width={args.width};preserveAspectRatio=1\x07'.encode('ascii'))
    for offset in range(0, len(encoded), 4096):
        out.write(b'\x1b]1337;FilePart=' + encoded[offset:offset + 4096] + b'\x07')
    out.write(b'\x1b]1337;FileEnd\x07\r\n')
    out.flush()

if __name__ == '__main__':
    main()
