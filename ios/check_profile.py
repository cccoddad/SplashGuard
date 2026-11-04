#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SplashGuard — iOS 描述文件结构校验

不依赖 macOS / Xcode / iPhone，纯 Python 检查 .mobileconfig 是否满足
Apple `com.apple.dnsSettings.managed` 载荷的必需结构，避免把坏文件传到手机上
才发现装不上。

退出码：0 通过，1 有错误。

用法：
    python ios/check_profile.py ios/SplashGuard-DNS-DoH.mobileconfig
    python ios/check_profile.py ios/*.mobileconfig     # 批量
"""

import plistlib
import sys
import uuid
from pathlib import Path

TOP_REQUIRED = [
    "PayloadType", "PayloadIdentifier", "PayloadUUID",
    "PayloadDisplayName", "PayloadVersion", "PayloadContent",
]
DNS_REQUIRED = [
    "PayloadType", "PayloadIdentifier", "PayloadUUID",
    "PayloadDisplayName", "PayloadVersion", "DNSProtocol", "ServerName",
]
PROTOCOLS = {"HTTPS", "TLS", "Cleartext"}


def is_uuid(value: str) -> bool:
    try:
        uuid.UUID(str(value))
        return True
    except (ValueError, AttributeError, TypeError):
        return False


def check(path: Path) -> list[str]:
    errs: list[str] = []
    try:
        with path.open("rb") as f:
            data = plistlib.load(f)
    except Exception as e:  # noqa: BLE001 - 报错原文对用户更有用
        return [f"无法解析 plist：{e}"]

    if not isinstance(data, dict):
        return ["根节点必须是 dict"]

    for k in TOP_REQUIRED:
        if k not in data:
            errs.append(f"顶层缺少 {k}")
    if data.get("PayloadType") != "Configuration":
        errs.append("顶层 PayloadType 应为 Configuration")
    if not is_uuid(data.get("PayloadUUID", "")):
        errs.append("顶层 PayloadUUID 不是合法 UUID")

    content = data.get("PayloadContent")
    if not isinstance(content, list) or not content:
        return errs + ["PayloadContent 必须是非空数组"]

    for i, p in enumerate(content):
        tag = f"PayloadContent[{i}]"
        if not isinstance(p, dict):
            errs.append(f"{tag} 必须是 dict")
            continue
        for k in DNS_REQUIRED:
            if k not in p:
                errs.append(f"{tag} 缺少 {k}")
        if p.get("PayloadType") != "com.apple.dnsSettings.managed":
            errs.append(f"{tag} PayloadType 应为 com.apple.dnsSettings.managed")
        if not is_uuid(p.get("PayloadUUID", "")):
            errs.append(f"{tag} PayloadUUID 不是合法 UUID")

        proto = p.get("DNSProtocol")
        if proto not in PROTOCOLS:
            errs.append(f"{tag} DNSProtocol 必须是 {sorted(PROTOCOLS)} 之一，当前 {proto!r}")
        if proto == "HTTPS" and "ServerURL" not in p:
            errs.append(f"{tag} DoH 模式必须提供 ServerURL")
        if "ServerURL" in p and not str(p["ServerURL"]).startswith("https://"):
            errs.append(f"{tag} ServerURL 必须是 https:// 开头，当前 {p['ServerURL']!r}")
        if proto in ("TLS", "Cleartext"):
            addrs = p.get("ServerAddresses")
            if not addrs and proto == "Cleartext":
                errs.append(f"{tag} Cleartext 模式必须提供 ServerAddresses")

        # 语义提醒（不算错误）
        if "SupplementalMatchDomains" not in p and "MatchDomains" not in p:
            pass  # 全局生效，符合预期

    uuids = [data.get("PayloadUUID")] + [p.get("PayloadUUID") for p in content if isinstance(p, dict)]
    if len(set(uuids)) != len(uuids):
        errs.append("存在重复的 PayloadUUID（iOS 会拒绝安装）")

    return errs


def describe(path: Path) -> str:
    with path.open("rb") as f:
        d = plistlib.load(f)
    p = d["PayloadContent"][0]
    target = p.get("ServerURL") or ",".join(p.get("ServerAddresses", [])) or "-"
    return (f"  name   : {d.get('PayloadDisplayName')}\n"
            f"  proto  : {p.get('DNSProtocol')}  sni={p.get('ServerName')}\n"
            f"  target : {target}\n"
            f"  scope  : {'全局所有查询' if 'MatchDomains' not in p and 'SupplementalMatchDomains' not in p else '限定域名'}")


def main() -> int:
    files = [Path(a) for a in sys.argv[1:]]
    if not files:
        files = sorted(Path(__file__).parent.glob("*.mobileconfig"))
    if not files:
        print("没有可校验的文件")
        return 1

    failed = 0
    for f in files:
        if not f.exists():
            print(f"[MISS] {f}")
            failed += 1
            continue
        errs = check(f)
        if errs:
            failed += 1
            print(f"[FAIL] {f}")
            for e in errs:
                print(f"       - {e}")
        else:
            print(f"[ OK ] {f}")
            print(describe(f))
    print(f"\n{len(files) - failed}/{len(files)} 通过")
    return 0 if failed == 0 else 1


if __name__ == "__main__":
    sys.exit(main())
