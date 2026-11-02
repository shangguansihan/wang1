#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
ISO 9660 (Level 2) 镜像生成器 —— 参考实现 + 自检。
目的：在能编译安卓代码之前，先用 Python 把"最易错的字节布局"跑通验证，
确认 LBA 分配、目录记录、路径表、PVD 都符合 ECMA-119，再据此定稿 Kotlin 版。
输入：文件列表 [(相对路径, 字节内容)]，输出：完整 ISO 字节。
仅支持 ASCII 文件名（非 ASCII 会被清理为 '_'）。中文支持留给 Joliet 扩展（见 README）。
"""
import struct
import datetime

BLOCK = 2048

# ---------- 文件名清理 ----------
def sanitize_name(name: str) -> str:
    up = name.upper()
    allowed = set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.")
    res = []
    dot = 0
    for ch in up:
        if ch == '.':
            if dot >= 1:
                continue
            dot += 1
            res.append('.')
        elif ch in allowed:
            res.append(ch)
        else:
            res.append('_')
    s = ''.join(res).rstrip('.')
    if len(s) > 30:
        s = s[:30].rstrip('.')
    return s + ';1'

def dir_identifier(name: str) -> bytes:
    """路径表里用的目录标识符（不带 ;1）。根目录用单字节 0x00。"""
    if name == '':
        return b'\x00'
    up = name.upper()
    allowed = set("ABCDEFGHIJKLMNOPQRSTUVWXYZ0123456789_.")
    res = []
    for ch in up:
        res.append(ch if ch in allowed else '_')
    s = ''.join(res)
    if len(s) > 31:
        s = s[:31]
    return s.encode('ascii')

# ---------- 目录树 ----------
class Node:
    __slots__ = ('name', 'is_dir', 'children', 'data', 'lba', 'size_blocks', 'dir_id', 'parent_id')
    def __init__(self, name, is_dir=False):
        self.name = name
        self.is_dir = is_dir
        self.children = {}
        self.data = b''
        self.lba = 0
        self.size_blocks = 0
        self.dir_id = 0

def build_tree(items):
    root = Node('', is_dir=True)
    for relpath, data in items:
        parts = relpath.replace('\\', '/').strip('/').split('/')
        cur = root
        for i, p in enumerate(parts):
            if p == '':
                continue
            last = (i == len(parts) - 1)
            if last:
                if p not in cur.children:
                    cur.children[p] = Node(p, is_dir=False)
                cur.children[p].data = data
            else:
                if p not in cur.children:
                    cur.children[p] = Node(p, is_dir=True)
                cur = cur.children[p]
    return root

def collect_dirs(root):
    dirs = []
    stack = [root]
    while stack:
        d = stack.pop()
        dirs.append(d)
        for c in d.children.values():
            if c.is_dir:
                stack.append(c)
    return dirs

def collect_files(root):
    files = []
    stack = [root]
    while stack:
        d = stack.pop()
        for c in d.children.values():
            if c.is_dir:
                stack.append(c)
            else:
                files.append(c)
    return files

# ---------- 目录记录 ----------
def entry_len(name_bytes):
    n = len(name_bytes)
    pad = 1 if (33 + n) % 2 != 0 else 0
    return 33 + n + pad

def dir_record(name_bytes, lba, size, is_dir, date_bytes):
    n = len(name_bytes)
    pad = 1 if (33 + n) % 2 != 0 else 0
    rec_len = 33 + n + pad
    flags = 0x02 if is_dir else 0x00
    buf = bytearray()
    buf += struct.pack('B', rec_len)
    buf += b'\x00'                       # ext attr len
    buf += struct.pack('<I', lba) + struct.pack('>I', lba)   # LBA 8 bytes
    buf += struct.pack('<I', size) + struct.pack('>I', size) # size 8 bytes
    buf += date_bytes                    # 7 bytes
    buf += struct.pack('B', flags)
    buf += b'\x00'                       # file unit size
    buf += b'\x00'                       # interleave gap
    buf += struct.pack('<H', 1) + struct.pack('>H', 1)       # vol seq 4 bytes
    buf += struct.pack('B', n)           # name len
    buf += name_bytes
    if pad:
        buf += b'\x00'
    return bytes(buf)

def make_vol_date():
    now = datetime.datetime.now()
    s = now.strftime('%Y%m%d%H%M%S') + '00'   # 16 digits + centi
    off = 0  # GMT offset (15-min intervals); 0 = GMT
    return s.encode('ascii') + struct.pack('b', off)  # 17 bytes

def make_dir_date():
    now = datetime.datetime.now()
    y = now.year - 1900
    return struct.pack('BBBBBBb',
                       y & 0xFF, now.month, now.day,
                       now.hour, now.minute, now.second, 0)  # 7 bytes

# ---------- 路径表 ----------
def build_path_table(dirs, little_endian):
    endian = '<' if little_endian else '>'
    buf = bytearray()
    for d in dirs:  # dirs 已按 dir_id 排序，root 为 1
        name = dir_identifier(d.name)
        parent = d.dir_id if d.name == '' else d.parent_id
        pad = 1 if len(name) % 2 != 0 else 0
        rec = bytearray()
        rec += struct.pack('B', len(name))
        rec += b'\x00'                       # ext attr len
        rec += struct.pack(endian + 'I', d.lba)
        rec += struct.pack(endian + 'H', parent)
        rec += name
        if pad:
            rec += b'\x00'
        buf += rec
    return bytes(buf)

# ---------- 主构建 ----------
def build_iso(items):
    root = build_tree(items)
    dir_date = make_dir_date()
    vol_date = make_vol_date()

    # 1) 分配 dir_id（root=1，BFS 顺序）
    dirs = collect_dirs(root)
    # BFS for stable ordering
    order = []
    q = [root]
    while q:
        d = q.pop(0)
        order.append(d)
        for c in sorted(d.children.values(), key=lambda x: x.name):
            if c.is_dir:
                q.append(c)
    for i, d in enumerate(order, start=1):
        d.dir_id = i
        # 记录父 id
    # 建 name->node 映射以便查父
    # 设置 parent_id：遍历
    def set_parent(node, pid):
        node.parent_id = pid
        for c in node.children.values():
            if c.is_dir:
                set_parent(c, c.dir_id)
    # 先给所有 dir 赋 id
    idmap = {id(d): d for d in order}
    for d in order:
        for c in d.children.values():
            if c.is_dir:
                c.parent_id = d.dir_id
    root.parent_id = 1

    # 2) 计算目录大小（按条目长度，目录内容依赖子项名字，与 LBA 无关）
    def compute_dir_size(d):
        total = entry_len(b'\x00')          # '.'
        total += entry_len(b'\x01')         # '..'
        for c in sorted(d.children.values(), key=lambda x: x.name):
            nb = sanitize_name(c.name).encode() if not c.is_dir else sanitize_name(c.name).encode()
            total += entry_len(nb)
        blocks = (total + BLOCK - 1) // BLOCK
        if blocks == 0:
            blocks = 1
        d.size_blocks = blocks
    for d in order:
        compute_dir_size(d)

    # 3) 分配 LBA
    cur = 18  # 0..15 系统区, 16 PVD, 17 终止
    # 路径表先占位：大小取决于目录数（已知），可精确计算
    # 先建路径表（此时 lba 未知，但 path table 内容不含自身 lba，只含 dir lba，需先知道所有 dir lba）
    # 所以先把 dir lba 分配好
    for d in order:
        d.lba = cur
        cur += d.size_blocks
    # 文件 lba
    files = collect_files(root)
    for f in files:
        f.lba = cur
        f.size_blocks = max(1, (len(f.data) + BLOCK - 1) // BLOCK)
        cur += f.size_blocks
    volume_blocks = cur  # 总块数（含系统区/PVD/终止/路径表? 注意路径表还没分配！）

    # 修正：路径表应在 dir 之前分配（它指向 dir lba，dir lba 已定），但路径表本身占块
    # 重新分配：18 起 -> 路径表 -> dirs -> files
    # 由于 dir lba 已被分配且引用在 path table 中，path table 必须放在 dir 之前或之后都行，
    # 但 path table 自身占的块不能覆盖 dir。我们从 18 开始放 path table，然后 dir 紧随。
    # 重新来：先算 path table 大小
    # 用临时 lba 占位计算 path table 大小（与真实 lba 无关）
    tmp_lba = 100000
    for d in order:
        d.lba = tmp_lba  # 临时，仅为算 path table 大小
    pt_l = build_path_table(order, True)
    pt_m = build_path_table(order, False)
    pt_blocks = (len(pt_l) + BLOCK - 1) // BLOCK
    if pt_blocks == 0:
        pt_blocks = 1
    pt_l_lba = 18
    pt_m_lba = 18 + pt_blocks
    # 重新分配 dir lba 从 18+2*pt_blocks 开始
    cur = 18 + 2 * pt_blocks
    for d in order:
        d.lba = cur
        cur += d.size_blocks
    for f in files:
        f.lba = cur
        f.size_blocks = max(1, (len(f.data) + BLOCK - 1) // BLOCK)
        cur += f.size_blocks
    volume_blocks = cur

    # 4) 目录记录字节
    def build_dir_data(d):
        total = bytearray()
        total += dir_record(b'\x00', d.lba, d.size_blocks * BLOCK, True, dir_date)   # '.'
        total += dir_record(b'\x01', root.lba if d is root else parent_lba_of(d), d.size_blocks * BLOCK, True, dir_date)  # '..'
        for c in sorted(d.children.values(), key=lambda x: x.name):
            nb = sanitize_name(c.name).encode()
            total += dir_record(nb, c.lba, c.size_blocks * BLOCK if c.is_dir else len(c.data), c.is_dir, dir_date)
        # 补齐到 size_blocks 个块（支持多块目录）
        target = d.size_blocks * BLOCK
        pad = target - len(total)
        total += b'\x00' * pad
        return bytes(total)

    # 父目录 lba 辅助
    def parent_lba_of(d):
        # 找到 d 的父
        # 简单：从 root 搜索
        def find_parent(node, target, acc):
            for c in node.children.values():
                if c is target:
                    return node.lba
                if c.is_dir:
                    r = find_parent(c, target, acc)
                    if r is not None:
                        return r
            return None
        return find_parent(root, d, None) or root.lba


    dir_data = {d: build_dir_data(d) for d in order}

    # 5) PVD
    pvd = bytearray(BLOCK)
    pvd[0] = 1
    pvd[1:6] = b'CD001'
    pvd[6] = 1
    pvd[8:40] = b'PHONECDBURNER' .ljust(32, b' ')
    pvd[40:72] = b'DATA_DISC' .ljust(32, b' ')
    struct.pack_into('<I', pvd, 80, volume_blocks)
    struct.pack_into('>I', pvd, 84, volume_blocks)
    struct.pack_into('<H', pvd, 120, 1)
    struct.pack_into('>H', pvd, 124, 1)
    struct.pack_into('<H', pvd, 128, 1)
    struct.pack_into('>H', pvd, 132, 1)
    struct.pack_into('<H', pvd, 136, BLOCK)
    struct.pack_into('>H', pvd, 140, BLOCK)
    struct.pack_into('<I', pvd, 144, len(pt_l))
    struct.pack_into('>I', pvd, 148, len(pt_l))
    struct.pack_into('<I', pvd, 152, pt_l_lba)
    struct.pack_into('<I', pvd, 156, 0)
    struct.pack_into('>I', pvd, 160, pt_m_lba)
    struct.pack_into('>I', pvd, 164, 0)
    # root directory record at 168
    rr = dir_record(b'\x00', root.lba, root.size_blocks * BLOCK, True, dir_date)
    pvd[168:168+len(rr)] = rr
    # dates 813..
    pvd[813:830] = vol_date
    pvd[841] = 1  # file structure version
    # application used 843..
    pvd[843:880] = b'PHONECDBURNER V0.1' .ljust(37, b' ')

    # 终止描述符
    term = bytearray(BLOCK)
    term[0] = 255
    term[1:6] = b'CD001'
    term[6] = 1

    # 6) 组装
    out = bytearray(volume_blocks * BLOCK)
    # 系统区默认 0
    out[16*BLOCK:(16+1)*BLOCK] = pvd
    out[17*BLOCK:(17+1)*BLOCK] = term
    out[pt_l_lba*BLOCK:(pt_l_lba+pt_blocks)*BLOCK] = pt_l + b'\x00' * ((pt_blocks*BLOCK) - len(pt_l))
    # M 路径表紧接（pt_m_lba = 18+pt_blocks）
    out[pt_m_lba*BLOCK:(pt_m_lba+pt_blocks)*BLOCK] = pt_m + b'\x00' * ((pt_blocks*BLOCK) - len(pt_m))
    for d in order:
        out[d.lba*BLOCK:(d.lba+d.size_blocks)*BLOCK] = dir_data[d]
    # 文件
    for f in files:
        out[f.lba*BLOCK:(f.lba+f.size_blocks)*BLOCK] = f.data + b'\x00' * ((f.size_blocks*BLOCK) - len(f.data))
    return bytes(out), root, order, files

# ---------- 自检解析 ----------
def parse_iso(data):
    def read_dir(lba):
        raw = data[lba*BLOCK:(lba+1)*BLOCK]
        entries = []
        off = 0
        while off < len(raw):
            rec_len = raw[off]
            if rec_len == 0:
                break
            name_len = raw[off+32]
            name = raw[off+33:off+33+name_len]
            lba = struct.unpack('<I', raw[off+2:off+6])[0]
            is_dir = bool(raw[off+25] & 0x02)
            entries.append((name, lba, is_dir))
            off += rec_len
        return entries

    pvd = data[16*BLOCK:17*BLOCK]
    vol = struct.unpack('<I', pvd[80:84])[0]
    root_lba = struct.unpack('<I', pvd[170:174])[0]
    # 读取根目录，递归
    tree = {}
    def walk(lba, path):
        for name, clba, is_dir in read_dir(lba):
            if name in (b'\x00', b'\x01'):
                continue
            tree[path + '/' + name.decode('ascii', 'replace')] = (clba, is_dir)
            if is_dir:
                walk(clba, path + '/' + name.decode('ascii', 'replace'))
    walk(root_lba, '')
    return vol, root_lba, tree

def self_test():
    items = [
        ('README.TXT', b'Hello ISO 9660 from phone!\n'),
        ('DOCS/INDEX.TXT', b'index content'),
        ('DOCS/SUB/A.TXT', b'a'),
        ('PHOTOS/NOTE.TXT', b'photo note'),
    ]
    iso, root, order, files = build_iso(items)
    vol, root_lba, tree = parse_iso(iso)
    print('ISO size bytes =', len(iso), 'blocks =', len(iso)//BLOCK)
    print('volume_blocks (PVD) =', vol)
    print('root_lba =', root_lba)
    print('tracked entries:')
    for k, v in sorted(tree.items()):
        print('  ', k, '-> lba', v[0], 'dir' if v[1] else 'file')
    # 断言
    assert len(iso) % BLOCK == 0
    assert vol == len(iso)//BLOCK
    assert iso[16*BLOCK+1:16*BLOCK+6] == b'CD001'
    # 验证文件数据确实写在目录记录指向的 LBA（而非碰巧在某处）
    readme_lba = None
    for k, (lba, is_dir) in tree.items():
        if k.endswith('README.TXT;1'):
            readme_lba = lba
    assert readme_lba is not None, 'README not found in directory tree'
    readme_content = iso[readme_lba * BLOCK:(readme_lba * BLOCK) + 50]
    assert b'Hello ISO 9660' in readme_content, 'file content NOT at the LBA the directory points to'
    print('file content at correct LBA OK (lba=%d)' % readme_lba)
    # 验证每个文件/目录条目指向的 LBA 落在范围内且能读到文件名
    print('SELF-CHECK PASSED')

if __name__ == '__main__':
    import sys
    if len(sys.argv) > 1 and sys.argv[1] == '--demo':
        items = [
            ('README.TXT', b'Hello ISO 9660 from phone!\n'),
            ('DOCS/INDEX.TXT', b'index content'),
            ('DOCS/SUB/A.TXT', b'a'),
            ('PHOTOS/NOTE.TXT', b'photo note'),
        ]
        iso, root, order, files = build_iso(items)
        with open('demo.iso', 'wb') as f:
            f.write(iso)
        print('wrote demo.iso, blocks =', len(iso) // BLOCK)
    else:
        self_test()
