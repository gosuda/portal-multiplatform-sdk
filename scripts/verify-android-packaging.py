#!/usr/bin/env python3
"""Check staged AAR and consumed APKs retain both 16 KiB-aligned engine ABIs."""
from pathlib import Path
import struct
import zipfile

root = Path(__file__).resolve().parents[1]
abis = {"arm64-v8a": 183, "x86_64": 62}
artifacts = [(path, "jni") for path in (root / "build/sample-maven/io/github/gosuda/portal-native-android").glob("*/*.aar")]
if len(artifacts) != 1:
    raise SystemExit(f"expected one staged native Android AAR, found {len(artifacts)}")
for variant in ("debug", "release"):
    apks = list((root / f"samples/android/build/outputs/apk/{variant}").glob("*.apk"))
    if len(apks) != 1:
        raise SystemExit(f"expected one {variant} APK, found {len(apks)}")
    artifacts.append((apks[0], "lib"))
for artifact, prefix in artifacts:
    with zipfile.ZipFile(artifact) as archive:
        for abi, machine in abis.items():
            content = archive.read(f"{prefix}/{abi}/libportaltunnel.so")
            if content[:6] != b"\x7fELF\x02\x01" or struct.unpack_from("<H", content, 18)[0] != machine:
                raise SystemExit(f"{artifact}: expected {abi} ELF64 binary")
            table = struct.unpack_from("<Q", content, 32)[0]
            entry_size, count = struct.unpack_from("<HH", content, 54)
            loads = 0
            for index in range(count):
                entry = table + index * entry_size
                if struct.unpack_from("<I", content, entry)[0] != 1:
                    continue
                loads += 1
                offset, address = struct.unpack_from("<QQ", content, entry + 8)
                alignment = struct.unpack_from("<Q", content, entry + 48)[0]
                if alignment < 16384 or offset % 16384 != address % 16384:
                    raise SystemExit(f"{artifact}: {abi} lost 16 KiB ELF alignment")
            if not loads:
                raise SystemExit(f"{artifact}: {abi} has no load segments")
    print(f"Verified both Android ABI architectures and 16 KiB alignment in {artifact}")
