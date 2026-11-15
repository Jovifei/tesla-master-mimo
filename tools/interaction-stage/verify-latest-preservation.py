#!/usr/bin/env python3
"""Read-only latest-base contract. Never contacts a provider or user device."""
from pathlib import Path
import json
import subprocess

ROOT = Path(__file__).resolve().parents[2]
def git(*args: str) -> str:
    return subprocess.check_output(["git", *args], cwd=ROOT, text=True).strip()

branch = git("rev-parse", "--abbrev-ref", "HEAD")
# Checkout can be detached under Actions; GITHUB_REF_NAME identifies only this job.
import os
branch = os.environ.get("GITHUB_REF_NAME", branch)
app = branch == "codex/app-interaction-latest-20261106"
mini = branch == "codex/miniprogram-interaction-latest-20261106"
assert app or mini, f"Not an authorized latest visual branch: {branch}"
base = ("e107773e18ec39a90ca63dffd58ab05e17b623c7" if app else
        "55d49b921f541c5c1d793c88e64dfaac51ee32b2")
subprocess.run(["git", "merge-base", "--is-ancestor", base, "HEAD"], cwd=ROOT, check=True)
protected = ["deploy", "web_matelink", "ios"]
if app:
    protected += ["miniprogram", "android/app/build.gradle.kts", "android/app/schemas",
                  "android/app/src/main/AndroidManifest.xml",
                  "android/app/src/main/java/com/matelink/data",
                  "android/app/src/main/java/com/matelink/domain",
                  "android/app/src/main/java/com/matelink/ui/screens",
                  "android/app/src/main/java/com/matelink/ui/navigation"]
else:
    protected += ["android/app/src/main", "android/app/build.gradle.kts",
                  "miniprogram/package.json", "miniprogram/package-lock.json",
                  "miniprogram/src/services/session.ts", "miniprogram/src/services/api.ts",
                  "miniprogram/src/services/cache_budget.ts",
                  "miniprogram/src/services/history_cache.ts",
                  "miniprogram/vehicle-assets"]
changes = git("diff", "--name-only", base, "HEAD", "--", *protected)
assert not changes, f"Latest functionality/data contracts were overwritten: {changes}"
forbidden = [p for p in git("diff", "--name-only", base, "HEAD").splitlines()
             if p.endswith((".jks", ".keystore", ".p12", ".pem", ".env", ".db"))]
assert not forbidden, forbidden
result = {"branch": branch, "head": git("rev-parse", "HEAD"), "base": base,
          "protected_paths": protected, "preserved": True, "evidence": "source-only"}
print(json.dumps(result, ensure_ascii=False, indent=2))
