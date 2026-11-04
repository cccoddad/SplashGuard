#!/usr/bin/env python3
# -*- coding: utf-8 -*-
"""
SplashGuard — iOS DNS 配置描述文件生成器

生成 com.apple.dnsSettings.managed 类型的 .mobileconfig，用于在 iOS 14+
上把全系统 DNS 指向一个支持加密查询（DoH / DoT）的上游解析器。

设计约束（很重要，决定了这个脚本能做什么、不能做什么）：
  * 描述文件只能改「DNS 解析」，不能改 App 的界面，也不能分应用。
  * 没有 DoH/DoT 服务端就没有本地规则引擎 —— iOS 端的所有过滤能力
    都来自你选择的上游解析器，本仓库不提供服务端。
  * UUID 由 PayloadIdentifier 派生（uuid5），保证同一份配置反复生成
    字节一致，便于 Git diff 与校验；换服务商 / 改名字才产生新 UUID。

用法：
    python ios/make_profile.py --preset adguard-doh
    python ios/make_profile.py --preset adguard-dot
    python ios/make_profile.py --preset nextdns --nextdns-id abc123
    python ios/make_profile.py --doh-url https://dns.example.com/dns-query \
                               --server-name dns.example.com \
                               --out ios/MyDNS.mobileconfig
"""

import argparse
import plistlib
import sys
import uuid
from pathlib import Path

NS = uuid.UUID("6f1c8f1e-2f4a-4d7b-9c3e-5a0b7d2e8c11")

PRESETS = {
    # 公共过滤型解析器，无需账号。默认推荐，装完即生效。
    "adguard-doh": {
        "label": "SplashGuard DNS (DoH)",
        "protocol": "HTTPS",
        "url": "https://dns.adguard-dns.com/dns-query",
        "server_name": "dns.adguard-dns.com",
    },
    "adguard-dot": {
        "label": "SplashGuard DNS (DoT)",
        "protocol": "TLS",
        "server_name": "dns.adguard-dns.com",
        "addresses": ["94.140.14.14", "94.140.15.15"],
    },
    # NextDNS 需要个人 ID，可自选拦截列表并可看查询日志（在 NextDNS 后台）
    "nextdns": {
        "label": "SplashGuard DNS (NextDNS)",
        "protocol": "HTTPS",
        "url": "https://dns.nextdns.io/{nextdns_id}",
        "server_name": "dns.nextdns.io",
    },
}


def det_uuid(identifier: str) -> str:
    return str(uuid.uuid5(NS, identifier))


def build_profile(label: str, protocol: str, server_name: str,
                  url: str | None = None, addresses: list[str] | None = None) -> dict:
    slug = "".join(c.lower() if c.isalnum() else "-" for c in label).strip("-")
    payload_id = f"com.splashguard.dns.{slug}"

    dns_payload = {
        "PayloadType": "com.apple.dnsSettings.managed",
        "PayloadIdentifier": payload_id,
        "PayloadUUID": det_uuid(payload_id),
        "PayloadDisplayName": label,
        "PayloadDescription": "将本设备的 DNS 查询加密转发至指定解析器，用于减少开屏广告与追踪域名解析。",
        "PayloadOrganization": "SplashGuard",
        "PayloadVersion": 1,
        "DNSProtocol": protocol,
        "ServerName": server_name,
    }
    if protocol == "HTTPS":
        if not url:
            raise SystemExit("DoH 配置必须提供 ServerURL")
        dns_payload["ServerURL"] = url
    if addresses:
        dns_payload["ServerAddresses"] = addresses

    # 不设置 SupplementalMatchDomains / MatchDomains => 作用于全部 DNS 查询。
    return {
        "PayloadType": "Configuration",
        "PayloadIdentifier": f"{payload_id}.profile",
        "PayloadUUID": det_uuid(f"{payload_id}.profile"),
        "PayloadDisplayName": label,
        "PayloadDescription": "SplashGuard 提供 · 全部解析在本机发起，SplashGuard 不收集任何数据。",
        "PayloadOrganization": "SplashGuard",
        "PayloadVersion": 1,
        "PayloadRemovalDisallowed": False,
        "PayloadContent": [dns_payload],
    }


def main() -> int:
    ap = argparse.ArgumentParser(description="生成 SplashGuard iOS DNS 描述文件")
    ap.add_argument("--preset", choices=sorted(PRESETS), help="使用内置服务商预设")
    ap.add_argument("--doh-url", help="自定义 DoH 地址，如 https://dns.example.com/dns-query")
    ap.add_argument("--server-name", help="自定义 TLS 的 SNI / 证书域名")
    ap.add_argument("--nextdns-id", help="NextDNS 配置 ID")
    ap.add_argument("--label", help="显示名称，默认取自预设")
    ap.add_argument("--out", help="输出路径")
    args = ap.parse_args()

    if args.preset:
        p = dict(PRESETS[args.preset])
        label = args.label or p["label"]
        if args.preset == "nextdns":
            if not args.nextdns_id:
                raise SystemExit("nextdns 预设需要 --nextdns-id")
            p["url"] = p["url"].format(nextdns_id=args.nextdns_id)
        profile = build_profile(
            label, p["protocol"], p["server_name"],
            url=p.get("url"), addresses=p.get("addresses"),
        )
        out = Path(args.out or f"ios/{args.preset.upper().replace('-', '_')}.mobileconfig")
    else:
        if not (args.doh_url and args.server_name):
            raise SystemExit("自定义模式需要同时给出 --doh-url 与 --server-name")
        label = args.label or "SplashGuard DNS (Custom)"
        profile = build_profile(label, "HTTPS", args.server_name, url=args.doh_url)
        out = Path(args.out or "ios/custom.mobileconfig")

    out.parent.mkdir(parents=True, exist_ok=True)
    with out.open("wb") as f:
        plistlib.dump(profile, f, fmt=plistlib.FMT_XML, sort_keys=False)
    print(f"written: {out}  ({out.stat().st_size} bytes)")
    print(f"payload: {profile['PayloadContent'][0]['PayloadIdentifier']}")
    print(f"uuid   : {profile['PayloadContent'][0]['PayloadUUID']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
