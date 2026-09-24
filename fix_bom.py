# -*- coding: utf-8 -*-
"""
把 .ps1 重存为 UTF-8 with BOM。
Windows PowerShell 5.1 读无 BOM 的 .ps1 会按系统 ANSI（中文机器上是 GBK）解码，
脚本里的中文路径和中文输出会全部变成乱码。
编辑过 .ps1 之后跑一次这个。
"""
import io
import os
import sys

HERE = os.path.dirname(os.path.abspath(__file__))
targets = sys.argv[1:] or ['setup_toolchain.ps1', 'build_apk.ps1']

for fn in targets:
    path = os.path.join(HERE, fn)
    if not os.path.exists(path):
        print('skip  %s (不存在)' % fn)
        continue
    text = io.open(path, encoding='utf-8-sig').read()   # utf-8-sig 读时会自动吃掉已有 BOM
    io.open(path, 'w', encoding='utf-8-sig', newline='\r\n').write(text)
    with open(path, 'rb') as f:
        has_bom = f.read(3) == b'\xef\xbb\xbf'
    print('%-22s %6d bytes  BOM=%s' % (fn, os.path.getsize(path), has_bom))
