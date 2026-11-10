#!/usr/bin/env python3
"""打包门禁：没有评审/验收签字的版本拒绝构建。

用法：python3 scripts/release_gate.py [--force]
退出码 0=通过，1=拒绝。
"""
import json
import re
import subprocess
import sys
from pathlib import Path

ROOT = Path(__file__).resolve().parents[1]
DOM = ROOT / "docs"


def fail(msg):
    print(f"[GATE] FAIL: {msg}")
    return 1


def read_version():
    props = {}
    for line in (ROOT / "android/version.properties").read_text().splitlines():
        if "=" in line:
            k, v = line.split("=", 1)
            props[k.strip()] = v.strip()
    manifest = (ROOT / "android/src/main/AndroidManifest.xml").read_text()
    m = re.search(r'android:versionCode="(\d+)"\s+android:versionName="([^"]+)"', manifest)
    return props, (m.group(1), m.group(2)) if m else (None, None)


def main():
    force = "--force" in sys.argv
    props, (mcode, mname) = read_version()
    vcode, vname = props.get("versionCode"), props.get("versionName")
    if (vcode, vname) != (mcode, mname):
        return fail(f"版本不一致 version.properties={vcode}/{vname} manifest={mcode}/{mname}")
    print(f"[GATE] version={vname} ({vcode})")

    dirty = subprocess.run(["git", "status", "--porcelain"], cwd=ROOT,
                           capture_output=True, text=True).stdout.strip()
    if dirty:
        return fail("工作树不干净，先提交改动")
    print("[GATE] tree clean")

    review = DOM / f"REVIEW_{vname}.md"
    qa = DOM / f"QA_{vname}.md"
    problems = []
    if not review.exists():
        problems.append(f"缺少架构评审 {review.name}")
    elif "结论：通过" not in review.read_text() and "结论: 通过" not in review.read_text():
        problems.append(f"{review.name} 未写「结论：通过」")
    if not qa.exists():
        problems.append(f"缺少 QA 验收 {qa.name}")
    elif "可打包" not in qa.read_text():
        problems.append(f"{qa.name} 未写「可打包」")
    if problems and not force:
        return fail("；".join(problems))
    for p in problems:
        print(f"[GATE] WARN(forced): {p}")
    if not problems:
        print(f"[GATE] review+QA signed for {vname}")
    print("[GATE] PASS")
    return 0


if __name__ == "__main__":
    sys.exit(main())
