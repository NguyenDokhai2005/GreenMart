import re, sys
src = open('_entities.iuml', encoding='utf-8').read()
head, rest = src.split('package "Danh mục và tổ chức"', 1)
rest = 'package "Danh mục và tổ chức"' + rest
body, rels = rest.split("' ---------- Quan hệ ----------", 1)

# tách entity blocks
ent_re = re.compile(r'(  entity "[^"]+" as (\w+)((?: \$\w+)*) \{\n.*?\n  \})', re.S)
ents = {m.group(2): (m.group(1), m.group(3).split()) for m in ent_re.finditer(body)}
pkg_re = re.compile(r'package "([^"]+)" \{\n(.*?)\n\}', re.S)

def strip_tags(block):
    return re.sub(r' (\$\w+)+(?= \{)', lambda m: '', re.sub(r'((?: \$\w+)+) \{', ' {', block, count=1))

def build(tag, title, name):
    keep = {a for a, (_, t) in ents.items() if tag is None or tag in t}
    out = ['@startuml ' + name, head.strip(), 'title ' + title, '']
    for pm in pkg_re.finditer(body):
        blocks = [strip_tags(b) for b in ent_re.findall(pm.group(2)) for b in [b[0]] if ent_re.match(b) and ent_re.match(b).group(2) in keep]
        if blocks:
            out.append('package "%s" {' % pm.group(1)); out += blocks; out.append('}')
    for line in rels.strip().splitlines():
        m = re.match(r'(\w+) \S+ (\w+)', line)
        if m and m.group(1) in keep and m.group(2) in keep:
            out.append(line)
    out.append('@enduml')
    open(name + '.puml', 'w', encoding='utf-8').write('\n'.join(out) + '\n')
    print(name, len(keep))

build(None, 'ERD đầy đủ – CSDL GreenMart (28 bảng)', '00-erd-day-du')
build('$sales', 'ERD – Bán hàng, khách hàng, khuyến mại', '01-erd-ban-hang')
build('$stock', 'ERD – Kho', '02-erd-kho')
build('$ops', 'ERD – Tích hợp kênh, hệ thống và dự báo', '03-erd-tich-hop-du-bao')
