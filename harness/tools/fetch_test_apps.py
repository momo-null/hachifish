# -*- coding: utf-8 -*-
"""M0' DoD4: fetch + install the 5 Fossify test apps on the current device.

F-Droid unreachable on this network -> use GitHub releases (FossifyOrg).
Flow per repo: latest release -> pick .apk asset (universal preferred) ->
aapt dump badging -> real package name -> adb install -r -g.

Usage: python fetch_test_apps.py [--repo NAME=apk_substring ...]
"""
import json
import subprocess
import sys
import urllib.request
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
sys.path.insert(0, str(ROOT / "harness"))
from _env import ADB, adb  # noqa: E402

AAPT = None
for cand in sorted((ROOT.parent / "Android" / "Sdk" / "build-tools").glob("*/aapt.exe")):
    AAPT = cand
if AAPT is None:
    for cand in sorted(Path("D:/Android/Sdk/build-tools").glob("*/aapt.exe")):
        AAPT = cand

# package id required by M0' DoD4 -> candidate github repos (tried in order)
WANTED = {
    "org.fossify.notes": ["Notes"],
    "org.fossify.math": ["Math", "Calculator"],
    "org.fossify.clock": ["Clock"],
    "org.fossify.gallery": ["Gallery"],
    "org.fossify.filemanager": ["File-Manager", "Simple-File-Manager"],
}
APK_DIR = ROOT / ".tools" / "apks"
# f-droid.org 与其官方镜像在本网络均不可达，仅 gh-proxy.com 通（2026-09-27 实测）。
# 下载后用 apksigner 交叉比对 5 个 APK 的签名证书（Fossify 全家桶同一证书）兜底。
GH_PROXY = "https://gh-proxy.com/"


def http_json(url, retries=4):
    last = None
    for i in range(retries):
        try:
            req = urllib.request.Request(url, headers={"User-Agent": "hachimi-setup"})
            with urllib.request.urlopen(req, timeout=30) as r:
                return json.loads(r.read().decode())
        except Exception as e:
            last = e
    raise last


def download(url, dest):
    # curl: resume + retries; GitHub objects CDN needs it on this network
    r = subprocess.run(["curl", "-sSL", "--retry", "5", "--retry-delay", "2",
                        "--retry-all-errors", "-C", "-", "-o", str(dest), url],
                       timeout=600)
    if r.returncode != 0:
        raise RuntimeError(f"curl exit {r.returncode}")


def apk_package(apk):
    out = subprocess.run([str(AAPT), "d", "badging", str(apk)],
                         capture_output=True, text=True, timeout=60)
    for line in out.stdout.splitlines():
        if line.startswith("package:"):
            return line.split("name='")[1].split("'")[0]
    return None


def sign_cert_sha(apk):
    signer = AAPT.parent / "apksigner.bat"
    if not signer.exists():
        return None
    out = subprocess.run([str(signer), "verify", "--print-certs", str(apk)],
                         capture_output=True, text=True, timeout=120)
    for line in out.stdout.splitlines():
        if "SHA-256" in line:
            return line.split()[-1]
    return None


def main():
    APK_DIR.mkdir(parents=True, exist_ok=True)
    done = {}
    for pkg, candidates in WANTED.items():
        rel = None
        used_repo = None
        for repo in candidates:
            try:
                rel = http_json(f"https://api.github.com/repos/FossifyOrg/{repo}/releases/latest")
                used_repo = repo
                break
            except Exception as e:
                print(f"[skip] {repo}: {e}")
        if rel is None:
            print(f"[FAIL] {pkg}: no reachable repo in {candidates}")
            continue
        assets = [a for a in rel.get("assets", []) if a["name"].lower().endswith(".apk")]
        if not assets:
            print(f"[FAIL] {used_repo}: no .apk asset in {rel.get('tag_name')}")
            continue
        # prefer universal / plain apk; avoid per-abi splits
        assets.sort(key=lambda a: ("universal" not in a["name"].lower(),
                                   "arm64" in a["name"].lower() or "armeabi" in a["name"].lower()
                                   or "x86" in a["name"].lower(), -a["size"]))
        a = assets[0]
        dest = APK_DIR / f"{pkg}.apk"
        try:
            if not dest.exists() or dest.stat().st_size != a["size"]:
                print(f"[get ] {used_repo} {rel['tag_name']} <- {a['name']} ({a['size'] // 1024} KB)")
                download(GH_PROXY + a["browser_download_url"], dest)
        except Exception as e:
            print(f"[FAIL] download {pkg}: {e}")
            continue
        real = apk_package(dest)
        print(f"[aapt] {dest.name} -> package {real}")
        if real and real != pkg:
            print(f"[WARN] expected {pkg}, got {real}; installing as-is, rename map needed")
        installed = adb("shell", "pm list packages", pkg).strip().splitlines()
        if any(l == f"package:{pkg}" for l in installed):
            print(f"[skip] {pkg} already installed")
            done[pkg] = True
            continue
        try:
            out = adb("install", "-r", "-g", str(dest), timeout=180)
            ok = "Success" in out
            print(f"[{'OK  ' if ok else 'FAIL'}] install {pkg}: "
                  f"{out.strip().splitlines()[-1][:80] if out.strip() else 'no output'}")
        except Exception as e:
            ok = False
            print(f"[FAIL] install {pkg}: {type(e).__name__}（ColorOS 安装确认弹窗未点？）")
        done[pkg] = ok
    certs = {}
    for pkg in WANTED:
        apk = APK_DIR / f"{pkg}.apk"
        if apk.exists():
            certs[pkg] = sign_cert_sha(apk)
    distinct = {v for v in certs.values() if v}
    if len(distinct) == 1:
        print(f"[sig ] {sum(1 for v in certs.values() if v)}/{len(WANTED)} APKs share one cert: "
              f"{next(iter(distinct))[:20]}...")
    elif len(distinct) == 0:
        print("[sig ] skipped (apksigner unavailable or no APKs)")
    else:
        print(f"[WARN] signature mismatch across APKs: {certs}")
    print(f"== summary: {sum(done.values())}/{len(WANTED)} installed ==")


if __name__ == "__main__":
    main()
