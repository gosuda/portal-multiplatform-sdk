#!/usr/bin/env python3
"""Record build provenance beside each native binary, then collect release hashes."""
import argparse
import hashlib
import json
from pathlib import Path
import shlex
import subprocess

ROOT = Path(__file__).resolve().parents[1]


def command(*args, cwd=ROOT):
    return subprocess.check_output(args, cwd=cwd, text=True).strip()


def sha256(path):
    digest = hashlib.sha256()
    with path.open("rb") as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b""):
            digest.update(chunk)
    return digest.hexdigest()


def bridge_hash():
    digest = hashlib.sha256()
    for path in sorted((ROOT / "native/bridge").rglob("*")):
        if path.is_file():
            digest.update(path.relative_to(ROOT).as_posix().encode() + b"\0")
            digest.update(bytes.fromhex(sha256(path)))
    return digest.hexdigest()


def record(args):
    binary = Path(args.binary).resolve()
    if not binary.is_file() or binary.stat().st_size == 0:
        raise ValueError(f"missing or empty native binary: {binary}")
    data = {
        "artifact": args.artifact,
        "sha256": sha256(binary),
        "size_bytes": binary.stat().st_size,
        "source_commit": command("git", "rev-parse", "HEAD"),
        "bridge_tree_sha256": bridge_hash(),
        "go_mod_sha256": sha256(ROOT / "native/bridge/go.mod"),
        "go_sum_sha256": sha256(ROOT / "native/bridge/go.sum"),
        "go_toolchain": command(args.go, "version", cwd=ROOT / "native/bridge"),
        "compiler_command": args.compiler,
        "compiler_version": command(*shlex.split(args.compiler), "--version"),
    }
    binary.with_name(binary.name + ".provenance.json").write_text(json.dumps(data, indent=2) + "\n")


def collect(args):
    lock = json.loads((ROOT / "native/source-lock.json").read_text())
    source_commit = command("git", "rev-parse", "HEAD")
    tree_hash = bridge_hash()
    for artifact, metadata in lock["artifacts"].items():
        path = ROOT / "native" / artifact
        if artifact.startswith("android/"):
            path = ROOT / "portal-native-android/build/generated/jniLibs" / artifact.removeprefix("android/")
        provenance = json.loads(path.with_name(path.name + ".provenance.json").read_text())
        for field, expected in {
            "artifact": artifact,
            "source_commit": source_commit,
            "bridge_tree_sha256": tree_hash,
            "go_mod_sha256": sha256(ROOT / "native/bridge/go.mod"),
            "go_sum_sha256": sha256(ROOT / "native/bridge/go.sum"),
            "sha256": sha256(path),
            "size_bytes": path.stat().st_size,
        }.items():
            if provenance.get(field) != expected:
                raise ValueError(f"{artifact}: provenance mismatch for {field}")
        metadata.update(provenance)
    lock["release_source_commit"] = source_commit
    output = Path(args.output)
    output.parent.mkdir(parents=True, exist_ok=True)
    output.write_text(json.dumps(lock, indent=2) + "\n")
    print(f"Recorded {len(lock['artifacts'])} verified native artifacts in {output}")


parser = argparse.ArgumentParser(description=__doc__)
commands = parser.add_subparsers(dest="mode", required=True)
build = commands.add_parser("record")
build.add_argument("artifact")
build.add_argument("binary")
build.add_argument("--compiler", required=True)
build.add_argument("--go", default="go")
build.set_defaults(run=record)
release = commands.add_parser("collect")
release.add_argument("output")
release.set_defaults(run=collect)
arguments = parser.parse_args()
arguments.run(arguments)
