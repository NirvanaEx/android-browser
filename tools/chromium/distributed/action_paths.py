"""Paths accepted by both host-tool and Android compiler waves."""
import pathlib
import re


def object_path(name):
    if not isinstance(name, str) or not re.fullmatch(r'[A-Za-z0-9_./+-]+\.o', name):
        return False
    parts = pathlib.PurePosixPath(name).parts
    return '..' not in parts and '.' not in parts and (
        parts[0] == 'obj' or (len(parts) > 2 and parts[0].startswith('clang_') and parts[1] == 'obj'))


def host_object(name):
    return object_path(name) and not name.startswith('obj/')
