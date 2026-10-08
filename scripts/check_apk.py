"""Prove model exclusion and ELF 16 KiB alignment without loading native code."""
import struct
import sys
import zipfile

with zipfile.ZipFile(sys.argv[1]) as archive:
    names = archive.namelist()
    assert 'classes.dex' in names and 'AndroidManifest.xml' in names
    assert not any('final.mdl' in n or 'vosk-model-small' in n for n in names), 'Modèle inclus dans APK'
    libraries = [n for n in names if n.startswith('lib/') and n.endswith('.so')]
    assert any('arm64-v8a/libvosk.so' in n for n in libraries)
    assert any('arm64-v8a/libjnidispatch.so' in n for n in libraries)
    for name in libraries:
        data = archive.read(name)
        assert data[:4] == b'\x7fELF' and data[4] == 2 and data[5] == 1, name
        phoff = struct.unpack_from('<Q', data, 32)[0]
        entsize, count = struct.unpack_from('<HH', data, 54)
        for i in range(count):
            pos = phoff + i * entsize
            if struct.unpack_from('<I', data, pos)[0] == 1:
                align = struct.unpack_from('<Q', data, pos + 48)[0]
                assert align >= 16384, f'{name}: ELF alignement {align}'
    print(f'APK : {len(libraries)} bibliothèques 16 KiB, modèle absent, dex présent')
