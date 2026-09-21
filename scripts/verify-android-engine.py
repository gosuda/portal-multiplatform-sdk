#!/usr/bin/env python3
"""Verify Android engine architecture, v1 exports, and 16 KiB ELF load alignment."""
from pathlib import Path
import re
import subprocess
import sys

abi, binary_name, toolchain_name = sys.argv[1:]
binary = Path(binary_name)
toolchain = Path(toolchain_name)
root = Path(__file__).resolve().parents[1]


def inspect(tool, *args):
    return subprocess.check_output([str(toolchain / tool), *args, str(binary)], text=True)


expected_machine = {"arm64-v8a": "AArch64", "x86_64": "Advanced Micro Devices X86-64"}[abi]
header = inspect("llvm-readelf", "--file-header")
if not re.search(r"Machine:\s*" + re.escape(expected_machine) + r"\s*$", header, re.MULTILINE):
    raise SystemExit(f"{binary}: expected {expected_machine} ELF")
loads = [line.split() for line in inspect("llvm-readelf", "--program-headers", "--wide").splitlines()
         if line.strip().startswith("LOAD ")]
if not loads:
    raise SystemExit(f"{binary}: no ELF load segments")
for fields in loads:
    offset, address, alignment = int(fields[1], 16), int(fields[2], 16), int(fields[-1], 16)
    if alignment < 16384 or offset % 16384 != address % 16384:
        raise SystemExit(f"{binary}: LOAD segment does not support 16 KiB pages: {' '.join(fields)}")
required = set(re.findall(r"\b(Portal\w+)\(", (root / "native/include/portaltunnel.h").read_text()))
exports = {line.split()[-1] for line in inspect("llvm-nm", "--dynamic", "--defined-only").splitlines() if line.split()}
missing = required - exports
if missing:
    raise SystemExit(f"{binary}: missing v1 exports: {sorted(missing)}")
generated = binary.with_suffix(".h")
if set(re.findall(r"\b(Portal\w+)\(", generated.read_text())) != required:
    raise SystemExit(f"{binary}: generated header differs from native/include/portaltunnel.h")
print(f"Verified {abi}: {len(required)} v1 exports, {len(loads)} aligned load segments")
