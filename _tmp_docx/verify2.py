import html
import re
import xml.etree.ElementTree as ET
import zipfile

W = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'


def flat(xml_bytes):
    root = ET.fromstring(xml_bytes)
    return ''.join(t.text or '' for t in root.iter(W + 't'))


for name in ('product', 'dev'):
    path = 'out-' + name + '.docx'
    z = zipfile.ZipFile(path)
    assert z.testzip() is None
    for n in z.namelist():
        if n.endswith('.xml') or n.endswith('.rels'):
            ET.fromstring(z.read(n))
    body = flat(z.read('word/document.xml'))
    head = flat(z.read('word/header1.xml')) if 'word/header1.xml' in z.namelist() else ''
    foot = ''
    for n in z.namelist():
        if re.match(r'word/footer\d*\.xml', n):
            foot += flat(z.read(n))
    print(name, path, 'body_chars', len(body))
    print('  封面版本行:', '版　本：1.0.0' in body)
    print('  摘要标题:', '摘要' in body)
    print('  去掉网页导航打开系统:', '打开系统' not in body)
    print('  本页已改本文:', '本页' not in body and body.count('本文') >= 1)
    print('  页眉:', head.strip()[:40])
    print('  页脚含页码域:', '第 ' in foot and '页 / 共' in foot)
    print('  首段:', body[:70].replace('\n', ' '))
