# -*- coding: utf-8 -*-
"""
对 HTML 里每个 <script> 块做括号平衡检查。

不是完整的 JS 解析器，但能抓住结构性错误（漏闭合、字符串没结束、正则字面量
被误当成除号）。这项目的失败模式是静默的，构建能过不代表页面能跑，
所以加一道最便宜的语法防线。

用法:  python check_js.py <file.html>
"""
import io
import re
import sys

SCRIPT = re.compile(r'<script\b[^>]*>(.*?)</script>', re.S)
BS, SQ, DQ, SL = chr(92), chr(39), chr(34), chr(47)
PAIRS = {')': '(', ']': '[', '}': '{'}
# '/' 出现在这些字符之后才是正则字面量的开始，否则是除号
PREV_OK = set('(,=:[!&|?{};+-*%~^<>') | {'\n'}


def check(js, label):
    i, n, line = 0, len(js), 1
    st = []

    def prev_sig(k):
        j = k - 1
        while j >= 0 and js[j] in ' \t':
            j -= 1
        return js[j] if j >= 0 else '\n'

    while i < n:
        c = js[i]
        if c == '\n':
            line += 1; i += 1; continue
        if c == SL and i + 1 < n and js[i + 1] == SL:
            while i < n and js[i] != '\n':
                i += 1
            continue
        if c == SL and i + 1 < n and js[i + 1] == '*':
            i += 2
            while i + 1 < n and not (js[i] == '*' and js[i + 1] == SL):
                if js[i] == '\n':
                    line += 1
                i += 1
            i += 2
            continue
        if c == SL and prev_sig(i) in PREV_OK:      # 正则字面量
            i += 1
            while i < n:
                if js[i] == BS:
                    i += 2; continue
                if js[i] == '[':
                    i += 1
                    while i < n and js[i] != ']':
                        if js[i] == BS:
                            i += 1
                        i += 1
                if js[i] == SL:
                    i += 1; break
                if js[i] == '\n':
                    break
                i += 1
            while i < n and js[i].isalpha():
                i += 1
            continue
        if c in (SQ, DQ):
            q = c; i += 1
            closed = False
            while i < n:
                if js[i] == BS:
                    i += 2; continue
                if js[i] == q:
                    i += 1; closed = True; break
                if js[i] == '\n':
                    line += 1
                i += 1
            if not closed:
                return '%s: 字符串从第 %d 行起没有闭合' % (label, line)
            continue
        if c in '([{':
            st.append((c, line))
        elif c in ')]}':
            if not st or st[-1][0] != PAIRS[c]:
                return '%s: 第 %d 行的 %s 对不上（栈尾 %s）' % (label, line, c, st[-3:])
            st.pop()
        i += 1

    if st:
        return '%s: 未闭合 %s' % (label, st[-4:])
    return None


def main():
    src = io.open(sys.argv[1], encoding='utf-8').read()
    blocks = SCRIPT.findall(src)
    if not blocks:
        sys.exit('找不到 <script> 块')
    bad = 0
    for idx, js in enumerate(blocks):
        label = 'script[%d]' % idx
        err = check(js, label)
        if err:
            print('  FAIL  %s' % err)
            bad += 1
        else:
            print('  OK    %-12s %5d 行' % (label, js.count('\n')))
    if bad:
        sys.exit(1)
    print('  %d 个 script 块括号平衡' % len(blocks))


if __name__ == '__main__':
    main()
