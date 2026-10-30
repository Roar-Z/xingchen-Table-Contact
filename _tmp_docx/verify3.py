import re
import xml.etree.ElementTree as ET
import zipfile

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


def norm(text):
    return re.sub(r'\s+', '', text or '')


def docx_text(path):
    z = zipfile.ZipFile(path)
    return norm(''.join(t.text or '' for t in ET.fromstring(z.read('word/document.xml')).iter(W + 't')))


# 只依赖标准库的粗略正文抽取：<p> / <li> / <td> / <th> / 标题 / code 行
def html_pieces(src):
    body = src[src.index('<div class="doc">'):src.index('</main>')]
    pieces = []
    for m in re.finditer(r'<(p|li|td|th|h2|h3|h4|pre)[^>]*>(.*?)</\1>', body, re.S):
        inner = re.sub(r'<[^>]+>', '', m.group(2))
        text = norm(inner)
        if not text:
            continue
        text = text.replace('本页', '本文')
        pieces.append(text)
    return pieces


for name in ('product', 'dev'):
    src = open('../xingchen-huibiao/frontend/public/docs/' + name + '.html', encoding='utf8').read()
    parts = html_pieces(src)
    # 网页页脚是导航，Word 里已经去掉；lead 的内容已经进了封面元信息
    text = docx_text('out-' + name + '.docx')
    missing = [p for p in parts if p not in text]
    # 表格里一格可能被拆成多个 run，退一步按前 40 字判断
    still_missing = []
    for p in missing:
        probe = p[:40]
        if probe and probe in text:
            continue
        still_missing.append(p[:40])
    print(name, 'pieces', len(parts), 'missing', len(still_missing))
    for p in still_missing[:6]:
        print('   -', p)
