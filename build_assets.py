# -*- coding: utf-8 -*-
"""
把 film-archive-app.html（设计原型）转换成安卓 App 用的独立页面。

原型是给浏览器/Artifact 看的：带手机外壳、masthead、规格说明区，依赖外部字体。
App 里要的是铺满屏幕的真界面 + 离线字体。

用法:  python build_assets.py
输出:  app/src/main/assets/index.html, app/src/main/assets/*.woff2
"""
import io
import os
import re
import sys
import urllib.request

HERE = os.path.dirname(os.path.abspath(__file__))
SRC = os.path.join(HERE, 'prototype', 'film-archive-app.html')
ASSETS = os.path.join(HERE, 'app', 'src', 'main', 'assets')
# 拍平：assets 下不放任何子目录。aapt2 在 Windows 上只会在【拼接子目录】时
# 把分隔符写成反斜杠（assets/fonts\foo.woff2），没有子目录就没有可拼接的部分，
# 安卓 AssetManager 按正斜杠建路径树才找得到。字体和模型都直接放 assets 根下。
FONTS = ASSETS

VOID = {'br', 'img', 'input', 'meta', 'link', 'hr', 'source', 'path',
        'rect', 'circle', 'use', 'stop', 'col', 'area', 'base', 'embed',
        'track', 'wbr', 'line', 'polygon', 'polyline', 'ellipse'}
TAG = re.compile(r'<(/?)([a-zA-Z][a-zA-Z0-9]*)((?:"[^"]*"|\'[^\']*\'|[^>"\'])*?)(/?)>')
SCRIPT = re.compile(r'<script\b[^>]*>(.*?)</script>', re.S)

# 首屏前定主题。放在 <style> 之后、<body> 之前，避免先闪一下暗色再跳到米色。
THEME_BOOT = (
    '<script>\n'
    '/* 首屏前定主题 —— 必须在样式之后、body 之前 */\n'
    '(function(){var t="darkroom";\n'
    'try{t=localStorage.getItem("filmbox.theme")||"darkroom";}catch(e){}\n'
    'document.documentElement.setAttribute("data-theme",t);})();\n'
    '</script>\n'
)


def extract_scripts(src):
    """
    按文档顺序返回所有 <script> 块的内容。

    不能用「第一个 <script> 到最后一个 </script>」切片：有多个块时那会把中间的
    </script><script> 一起吞进去，今天歪打正着，将来必出 bug。显式匹配每个块。
    """
    return [m.group(1) for m in SCRIPT.finditer(src)]


def extract_block(src, open_tag):
    """Return the full outerHTML of the element starting at open_tag (depth matched)."""
    start = src.index(open_tag)
    depth = 0
    for m in TAG.finditer(src, start):
        closing, name, _attrs, selfclose = m.group(1), m.group(2).lower(), m.group(3), m.group(4)
        if name in VOID or selfclose:
            continue
        if closing:
            depth -= 1
            if depth == 0:
                return src[start:m.end()]
        else:
            depth += 1
    raise RuntimeError('unbalanced: ' + open_tag)


UA = ('Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 '
      '(KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36')

# Space Mono 是整套视觉的签名元素（片边代号 / 帧号 / 日期），必须打进包里。
# Google Fonts 在国内时通时不通，所以备了镜像。
CSS_SOURCES = [
    'https://fonts.googleapis.com/css2?family=Space+Mono:wght@400;700&display=swap',
    'https://fonts.font.im/css2?family=Space+Mono:wght@400;700&display=swap',
]
FALLBACK = {
    '400': 'https://cdn.jsdelivr.net/npm/@fontsource/space-mono@5/files/space-mono-latin-400-normal.woff2',
    '700': 'https://cdn.jsdelivr.net/npm/@fontsource/space-mono@5/files/space-mono-latin-700-normal.woff2',
}


def _get(url, timeout=45):
    req = urllib.request.Request(url, headers={'User-Agent': UA})
    return urllib.request.urlopen(req, timeout=timeout).read()


def _latin_urls(css):
    """子集名写在 @font-face 之前的注释里（/* latin */），不在块内部，所以要把注释一起匹配。"""
    found = {}
    for m in re.finditer(r'/\*\s*([\w-]+)\s*\*/\s*@font-face\s*\{([^}]*)\}', css):
        if m.group(1) != 'latin':
            continue
        w = re.search(r'font-weight:\s*(\d+)', m.group(2))
        u = re.search(r'url\((https?://[^)]+\.woff2)\)', m.group(2))
        if w and u:
            found[w.group(1)] = u.group(1)
    return found


def fetch_fonts():
    """拉 Space Mono 400/700 的 latin 子集。失败不致命 —— 退回系统等宽字体。"""
    os.makedirs(FONTS, exist_ok=True)

    urls = {}
    for css_url in CSS_SOURCES:
        try:
            urls = _latin_urls(_get(css_url).decode('utf-8'))
        except Exception as e:
            print('  %s 不可用 (%s)' % (css_url.split('/')[2], type(e).__name__))
            continue
        if len(urls) >= 2:
            print('  %s: %d 个 latin 子集' % (css_url.split('/')[2], len(urls)))
            break
        print('  %s: 只解析到 %d 个' % (css_url.split('/')[2], len(urls)))

    faces = []
    for weight in ('400', '700'):
        path = os.path.join(FONTS, 'spacemono-%s.woff2' % weight)
        # 之前失败留下的 0 字节文件要重下
        if os.path.exists(path) and os.path.getsize(path) == 0:
            os.remove(path)
        if not os.path.exists(path):
            for src in (urls.get(weight), FALLBACK[weight]):
                try:
                    data = _get(src)          # 先整个下到内存，成功了再落盘
                    if not data:
                        continue
                    with open(path, 'wb') as f:
                        f.write(data)
                    print('  %s <- %s' % (os.path.basename(path), src.split('/')[2]))
                    break
                except Exception:
                    continue
        if os.path.exists(path) and os.path.getsize(path) > 0:
            print('  font  %-22s %6.1f KB' % (os.path.basename(path),
                                              os.path.getsize(path) / 1024.0))
            faces.append("@font-face{font-family:'Space Mono';font-style:normal;"
                         "font-weight:%s;font-display:swap;"
                         "src:url('%s') format('woff2');}"
                         % (weight, os.path.basename(path)))
        else:
            print('  font  %s 取不到，退回系统 monospace' % weight)
    return '\n'.join(faces)


APP_CSS = """
/* ============================================================
   App 模式：铺满设备视口，接管安全区
   （原型里的 .page / .hero / .spec / footer 规则留着不匹配即可）
   ============================================================ */
html, body {
  height: 100%;
  margin: 0;
  padding: 0;
  overflow: hidden;
  background: #15110D;
  overscroll-behavior: none;
}
body {
  -webkit-tap-highlight-color: transparent;
  -webkit-user-select: none;
  user-select: none;
  -webkit-touch-callout: none;
}
.phone {
  width: 100%;
  height: 100%;
  max-height: none;
  min-height: 0;
  border: 0;
  border-radius: 0;
  box-shadow: none;
}
.statusbar { display: none; }   /* 系统状态栏是真的，别画假的 9:41 */
.homebar   { display: none; }   /* 系统手势条本来就在 */
.nav       { padding-bottom: calc(9px + env(safe-area-inset-bottom, 0px)); }
.fab       { bottom: calc(70px + env(safe-area-inset-bottom, 0px)); }
.toast     { bottom: calc(108px + env(safe-area-inset-bottom, 0px)); }
.sheet     { padding-bottom: calc(20px + env(safe-area-inset-bottom, 0px)); }

/* 移动端没有 hover，把悬停反馈收掉 */
.card:hover, .row:hover, .gearcard:hover, .stockrow:hover,
.segbar:hover, .segitem:hover, .btn:hover, .iconbtn:hover, .opt:hover {
  border-color: var(--bd);
  color: inherit;
}
.btn:hover { background: var(--card2); }
.btn.primary:hover { background: var(--am); }
.iconbtn:hover { color: var(--tx2); }
.segbar:hover { background: rgba(229,160,58,.09); }
.segitem:hover { border-left-color: var(--am); }
.opt:hover { border-color: var(--bd); }
.fab:hover { background: var(--am); transform: none; }
"""


def main():
    if not os.path.exists(SRC):
        sys.exit('找不到原型文件: %s' % SRC)
    src = io.open(SRC, encoding='utf-8').read()

    css = src[src.index('<style>') + 7: src.index('</style>')]
    scripts = extract_scripts(src)
    if not scripts:
        sys.exit('原型里找不到 <script> 块')
    phone = extract_block(src, '<div class="phone" id="phone">')

    print('  source  %d KB css / %d 个 script 块 / %d 行 js'
          % (len(css) / 1024.0, len(scripts), sum(s.count('\n') for s in scripts)))
    os.makedirs(ASSETS, exist_ok=True)
    faces = fetch_fonts()
    # 抠图功能已移除，不再拉 magic_touch.tflite

    # 原型把 Noto Serif SC / Noto Sans SC 放在最前，安卓上没有这两个名字，
    # 会落到后面的 serif / sans-serif —— 正好是系统的 Noto CJK，中文没问题。
    out = (
        '<!doctype html>\n'
        '<html lang="zh-CN">\n'
        '<head>\n'
        '<meta charset="utf-8">\n'
        '<meta name="viewport" content="width=device-width,initial-scale=1,'
        'viewport-fit=cover,user-scalable=no">\n'
        '<meta name="theme-color" content="#15110D">\n'
        '<title>菲林档案</title>\n'
        '<style>\n' + faces + '\n' + css + '\n' + APP_CSS + '\n</style>\n'
        # 必须在 <style> 之后、<body> 之前：这样 data-theme 在首次绘制前就位，
        # 不会先闪一下暗色再跳到米色
        + THEME_BOOT +
        '</head>\n'
        '<body>\n' + phone + '\n'
        + ''.join('<script>\n' + s + '\n</script>\n' for s in scripts) +
        '</body>\n'
        '</html>\n'
    )

    os.makedirs(ASSETS, exist_ok=True)
    dst = os.path.join(ASSETS, 'index.html')
    io.open(dst, 'w', encoding='utf-8').write(out)
    print('  wrote   %s  (%.1f KB)' % (dst, len(out.encode('utf-8')) / 1024.0))


if __name__ == '__main__':
    main()
