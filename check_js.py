# -*- coding: utf-8 -*-
"""
对原型做三道最便宜的静态检查。都不是完整的解析器，但每一条都对应一个
真实踩过的坑 —— 这三类错误全都不报错，只表现为「界面有点不对劲」。

1. 每个 <script> 块的括号是否平衡
2. 每个 data-act 是否都有对应的点击处理
   （pickFilm 是 filmPresetChips('pickFilm') 动态生成的，字面量里搜不到，
     最早的脚本因此放过了它 —— 结果是点热门胶卷预设没反应）
3. 每个 I.xxx 图标引用是否都有定义
   （写错会渲染成字符串 "undefined"，界面上是个突兀的灰字，不报错）

用法:  python check_js.py [file.html]
"""
import io
import re
import sys

SCRIPT = re.compile(r'<script\b[^>]*>(.*?)</script>', re.S)
BS, SQ, DQ, SL = chr(92), chr(39), chr(34), chr(47)
PAIRS = {')': '(', ']': '[', '}': '{'}
# '/' 出现在这些字符之后才是正则字面量的开始，否则是除号
PREV_OK = set('(,=:[!&|?{};+-*%~^<>') | {'\n'}


def check_braces(js, label):
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


def check_acts(src):
    """所有 data-act 都要有点击处理。动态生成的那批也要算上。"""
    anchor = "document.getElementById('phone').addEventListener('click'"
    if anchor not in src:
        return ['找不到点击监听，检查脚本需要更新'], 0
    click = src[src.index(anchor):]
    keydown = "document.addEventListener('keydown'"
    if keydown in click:
        click = click[:click.index(keydown)]

    acts = set(re.findall(r'data-act=["\']([a-zA-Z]+)', src))
    acts |= set(re.findall(r"setAttribute\('data-act',\s*'([a-zA-Z]+)'", src))
    acts |= set(re.findall(r"filmPresetChips\('([a-zA-Z]+)'\)", src))

    return sorted(a for a in acts if ("case '" + a + "':") not in click), len(acts)


def check_icons(src):
    """I.xxx 引用都要有定义 —— 写错会渲染成字符串 "undefined"。"""
    script = src[src.rindex('<script>'):]
    m = re.search(r'var I = \{(.*?)\n\};', script, re.S)
    if not m:
        return ['找不到图标表 I'], 0
    defined = set(re.findall(r'^\s*([a-zA-Z]+)\s*:', m.group(1), re.M))
    used = set(re.findall(r'\bI\.([a-zA-Z]+)', script))
    return sorted(used - defined), len(defined)


def main():
    path = sys.argv[1] if len(sys.argv) > 1 else 'prototype/film-archive-app.html'
    src = io.open(path, encoding='utf-8').read()
    blocks = SCRIPT.findall(src)
    if not blocks:
        sys.exit('找不到 <script> 块')

    bad = 0
    for idx, js in enumerate(blocks):
        err = check_braces(js, 'script[%d]' % idx)
        if err:
            print('  FAIL  %s' % err)
            bad += 1
        else:
            print('  OK    %-12s %5d 行' % ('script[%d]' % idx, js.count('\n')))

    missing, total = check_acts(src)
    if missing:
        print('  FAIL  data-act 缺处理: %s' % ', '.join(missing))
        bad += 1
    else:
        print('  OK    data-act    %5d 个，全部有处理' % total)

    bad_icons, icon_total = check_icons(src)
    if bad_icons:
        print('  FAIL  图标用了但没定义: %s' % ', '.join(bad_icons))
        bad += 1
    else:
        print('  OK    icons      %5d 个，全部有定义' % icon_total)

    if bad:
        sys.exit(1)
    print('  检查通过')


if __name__ == '__main__':
    main()
