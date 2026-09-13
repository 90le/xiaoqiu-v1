import re, os, sys
dist = 'dist'
html = open(f'{dist}/index.html', encoding='utf-8').read()
# 内联 CSS
for m in re.finditer(r'<link rel="stylesheet"[^>]*href="\.?/?(assets/[^"]+)"[^>]*>', html):
    css = open(os.path.join(dist, m.group(1)), encoding='utf-8').read()
    html = html.replace(m.group(0), '<style>' + css + '</style>')
# 内联 JS（module）
for m in re.finditer(r'<script type="module"[^>]*src="\.?/?(assets/[^"]+)"[^>]*></script>', html):
    js = open(os.path.join(dist, m.group(1)), encoding='utf-8').read()
    html = html.replace(m.group(0), '<script type="module">' + js + '</script>')
open(f'{dist}/index.html','w',encoding='utf-8').write(html)
print('内联完成，index.html 大小:', len(html))
