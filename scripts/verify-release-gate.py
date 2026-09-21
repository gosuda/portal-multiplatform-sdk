#!/usr/bin/env python3
"""Permit automatic verification only on release tags; publication always needs one."""
import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tomllib

parser = argparse.ArgumentParser(description=__doc__)
parser.add_argument("--publish", action="store_true", help="also enforce version/provenance publication gates")
args = parser.parse_args()
root = Path(__file__).resolve().parents[1]
event = os.environ.get("GITHUB_EVENT_NAME", "workflow_dispatch")
ref_type = os.environ.get("GITHUB_REF_TYPE", "")
ref_name = os.environ.get("GITHUB_REF_NAME", "")

if event != "workflow_dispatch" or args.publish:
    if ref_type != "tag" or not ref_name.startswith("release-"):
        raise SystemExit("release gate: a release-* tag is required (manual verification may run on a branch)")
    subject = subprocess.check_output(["git", "log", "-1", "--format=%s", "HEAD"], cwd=root, text=True).strip()
    if not re.match(r"^release\([^()\s]+\):\s+\S", subject):
        raise SystemExit(f"release gate: tag points to a non-release(scope): commit: {subject}")

if args.publish:
    versions = tomllib.loads((root / "gradle/libs.versions.toml").read_text())["versions"]
    expected = f"release-v{versions['portal-sdk']}"
    if ref_name != expected:
        raise SystemExit(f"release gate: expected {expected}, found {ref_name}")
    lock_path = root / "native/source-lock.json"
    text = lock_path.read_text()
    json.loads(text)
    unresolved = re.findall(r".*(?:UNRESOLVED|PENDING|MISSING|TO_BE_FILLED).*", text)
    if unresolved:
        raise SystemExit("release gate: unresolved native provenance/licensing:\n" + "\n".join(unresolved))
print("Release publication gates passed" if args.publish else "Release verification eligibility passed")
