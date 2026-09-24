# -*- coding: utf-8 -*-
"""
APK 里 zip 条目名的路径分隔符 —— 检查（并可选修复）。

背景：aapt2 在 Windows 上遍历 assets 目录时，【拼接子目录】的部分会写成反斜杠
（assets/fonts\\spacemono-400.woff2）。ZIP 规范要求正斜杠，安卓的 AssetManager
也按正斜杠建路径树 —— 带反斜杠的条目在运行时是找不到的，页面会静默退回系统字体，
不报任何错。这条路径已经静默坏过一次，所以每次构建都要验。

现在 assets 已拍平（不放任何子目录），没有可拼接的部分，理论上不会再出现。
本脚本因此降级为构建后的**断言**，而不是修复步骤。

坑：Python 的 zipfile 在 Windows 上读 zip 时会把 \\ 自动换成 /
（ZipInfo.__init__ 里按 os.sep 做归一化），所以直接看 .filename
永远看不到问题，必须看 .orig_filename（归一化之前的原始名）。

用法:
  python fix_apk_paths.py --assert <apk>    # 断言，发现反斜杠就 exit 1（构建用）
  python fix_apk_paths.py <apk>             # 就地修复（备用）
"""
import os
import sys
import zipfile


def scan(apk):
    """返回 (原始名列表) —— 用 orig_filename 才看得到反斜杠。"""
    zin = zipfile.ZipFile(apk, 'r')
    bad = [i.orig_filename for i in zin.infolist() if '\\' in i.orig_filename]
    zin.close()
    return bad


def repair(apk):
    zin = zipfile.ZipFile(apk, 'r')
    bad = [i for i in zin.infolist() if '\\' in i.orig_filename]
    if not bad:
        zin.close()
        return 0

    tmp = apk + '.norm'
    zout = zipfile.ZipFile(tmp, 'w', zipfile.ZIP_DEFLATED)
    for item in zin.infolist():
        name = item.orig_filename.replace('\\', '/')
        zi = zipfile.ZipInfo(name, date_time=item.date_time)
        # 原样保留压缩方式：STORED 的条目（resources.arsc 等）必须保持不压缩，
        # 否则 zipalign 没法给它做 4 字节对齐，安卓会加载失败
        zi.compress_type = item.compress_type
        zi.external_attr = item.external_attr
        zi.internal_attr = item.internal_attr
        zi.create_system = item.create_system
        zout.writestr(zi, zin.read(item.filename))
    zin.close()
    zout.close()
    os.replace(tmp, apk)
    return len(bad)


if __name__ == '__main__':
    args = sys.argv[1:]
    asserting = '--assert' in args
    rest = [a for a in args if a != '--assert']
    if not rest:
        sys.exit('用法: python fix_apk_paths.py [--assert] <apk>')
    target = rest[0]

    bad = scan(target)
    if asserting:
        if bad:
            print('  断言失败：%d 个条目名含反斜杠，安卓运行时找不到这些资源' % len(bad))
            for x in bad:
                print('    %s' % x)
            sys.exit(1)
        print('  条目名断言通过（全部正斜杠）')
        sys.exit(0)

    if not bad:
        print('  条目名无需修正（全部已是正斜杠）')
        sys.exit(0)
    n = repair(target)
    print('  修正了 %d 个反斜杠条目名' % n)
