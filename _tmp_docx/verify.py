import html
import re
import xml.etree.ElementTree as ET
import zipfile

NS = '{http://schemas.openxmlformats.org/wordprocessingml/2006/main}'

for name in ('product', 'dev'):
    src = open('frontend/public/docs/' + name + '.html', encoding='utf8').read()
    start = src.index('<div class="doc">')
    end = src.index('</main>')
    body = src[start:end]
    body = re.sub(r'<[^>]+>', '', body)
    h = re.sub(r'\s+', '', html.unescape(body))
    z = zipfile.ZipFile('frontend/public/docs/' + name + '.docx')
    d = ''.join(x.text or '' for x in ET.fromstring(z.read('word/document.xml')).iter(NS + 't'))
    d = re.sub(r'\s+', '', d)
    print(name, 'html', len(h), 'docx', len(d), 'equal', h == d)
    if h != d:
        i = 0
        while i < min(len(h), len(d)) and h[i] == d[i]:
            i += 1
        print('  diff at', i, repr(h[i:i + 30]), repr(d[i:i + 30]))
