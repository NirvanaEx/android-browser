"""Reuse successful pinned dependency setup only from verified workspace archives."""
from common import read, sha, write

MARKER = '.upgrid-cloud-source-setup.json'
INPUTS = ('.gclient', '.gclient_entries', 'src/DEPS',
          'src/third_party/ninja/ninja',
          'src/third_party/llvm-build/Release+Asserts/bin/clang++',
          'src/third_party/rust-toolchain/bin/rustc')


def identity(root, tools):
    if any(not (root / name).is_file() for name in INPUTS):
        return None
    sdk = root / 'src/third_party/android_sdk/public/build-tools'
    sdk_tools = sorted([*sdk.glob('*/aapt'), *sdk.glob('*/apksigner')])
    if {path.name for path in sdk_tools} != {'aapt', 'apksigner'}:
        return None
    return dict(schema=1, setupSha256=sha(tools / 'prepare.py'),
                pinned=read(tools / 'upstream.json'),
                files={name: sha(root / name) for name in INPUTS},
                androidTools={path.relative_to(root).as_posix(): sha(path) for path in sdk_tools})


def reusable(root, tools):
    marker = root / MARKER
    current = identity(root, tools)
    return bool(marker.exists() and current is not None and read(marker) == current)


def save(root, tools):
    current = identity(root, tools)
    if current is not None:
        write(root / MARKER, current)
