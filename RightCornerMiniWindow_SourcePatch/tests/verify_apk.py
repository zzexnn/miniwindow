"""Verify LSPosed entry metadata and all uncompressed arm64 ELF/ZIP 16KB alignment."""
import hashlib, struct, sys, zipfile
from pathlib import Path
apk=Path(sys.argv[1])
with zipfile.ZipFile(apk) as z:
    entry='META-INF/xposed/'
    assert z.read(entry+'java_init.list').decode().strip() == 'com.lsp.hypersidebar.XposedInit'
    scope=set(z.read(entry+'scope.list').decode().split())
    assert scope == {'com.miui.home','com.miui.securitycenter','com.android.systemui'},scope
    props=z.read(entry+'module.prop').decode()
    assert 'minApiVersion=102' in props
    assert 'hypersidebar_native' in z.read(entry+'native_init.list').decode()
    libs=[i for i in z.infolist() if i.filename.startswith('lib/arm64-v8a/') and i.filename.endswith('.so')]
    assert any(i.filename.endswith('libhypersidebar_native.so') for i in libs)
    with apk.open('rb') as f:
        for i in libs:
            assert i.compress_type == zipfile.ZIP_STORED, i.filename
            f.seek(i.header_offset)
            header=f.read(30)
            name_size, extra_size=struct.unpack_from('<HH',header,26)
            offset=i.header_offset+30+name_size+extra_size
            assert offset%16384 == 0,(i.filename,offset)
            data=z.read(i)
            assert data[:5] == b'\x7fELF\x02',i.filename
            phoff=struct.unpack_from('<Q',data,32)[0]
            phsize,phnum=struct.unpack_from('<HH',data,54)
            for n in range(phnum):
                off=phoff+n*phsize
                if struct.unpack_from('<I',data,off)[0] == 1:
                    align=struct.unpack_from('<Q',data,off+48)[0]
                    assert align >= 16384,(i.filename,align)
print(f'PASS: LSPosed entry, three scopes, native entry, {len(libs)} arm64 libraries aligned to 16KB')
print('SHA256',hashlib.sha256(apk.read_bytes()).hexdigest())
