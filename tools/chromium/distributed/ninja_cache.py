"""Strict reader and append-only writer for Ninja's documented v4 deps log.

Format reference: ninja-build/ninja src/deps_log.cc (Apache-2.0).
Never open a live build's log for writing; callers must hold the build boundary.
"""
import struct

HEADER = b'# ninjadeps\n\x04\x00\x00\x00'


def read_deps(file, paths_only=False):
    paths, records = [], {}
    with open(file, 'rb') as stream:
        if stream.read(16) != HEADER:
            raise ValueError('Unknown Ninja deps format')
        while raw := stream.read(4):
            if len(raw) != 4:
                raise ValueError('Truncated Ninja record')
            field, = struct.unpack('<I', raw)
            size = field & 0x7fffffff
            if size > (1 << 19) - 1 or size < 4 or size % 4:
                raise ValueError('Invalid Ninja record size')
            data = stream.read(size)
            if len(data) != size:
                raise ValueError('Truncated Ninja payload')
            if field & 0x80000000:
                if size < 12:
                    raise ValueError('Invalid dependency record')
                output, mtime = struct.unpack('<IQ', data[:12])
                ids = struct.unpack('<' + 'I' * ((size - 12) // 4), data[12:])
                if output >= len(paths) or any(index >= len(paths) for index in ids):
                    raise ValueError('Unknown dependency path ID')
                if not paths_only:
                    records[paths[output]] = (mtime, tuple(paths[index] for index in ids))
            else:
                checksum, = struct.unpack('<I', data[-4:])
                if checksum != (len(paths) ^ 0xffffffff):
                    raise ValueError('Ninja path checksum mismatch')
                name = data[:-4].rstrip(b'\0').decode('utf-8')
                if not name or '\0' in name:
                    raise ValueError('Invalid Ninja path')
                paths.append(name)
    return paths, records


def append_deps(file, existing_paths, records):
    ids = {name: index for index, name in enumerate(existing_paths)}
    with open(file, 'ab') as stream:
        for name, (mtime, dependencies) in records.items():
            for item in (name, *dependencies):
                if item in ids:
                    continue
                index = len(ids)
                data = item.encode('utf-8')
                data += b'\0' * ((-len(data)) % 4)
                data += struct.pack('<I', index ^ 0xffffffff)
                stream.write(struct.pack('<I', len(data)) + data)
                ids[item] = index
            data = struct.pack('<IQ', ids[name], mtime)
            data += struct.pack('<' + 'I' * len(dependencies), *(ids[item] for item in dependencies))
            if len(data) > (1 << 19) - 1:
                raise ValueError('Dependency record too large')
            stream.write(struct.pack('<I', len(data) | 0x80000000) + data)
    return list(ids)
