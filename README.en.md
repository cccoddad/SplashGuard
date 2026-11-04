# SplashGuard

English | [简体中文](README.md)

Android local splash-ad blocker. **No root, no jailbreak, no data collection.**

It uses a local VPN to take over DNS queries only (all other traffic bypasses the tunnel), so ad domains fail to resolve. An optional accessibility service then taps the same "Skip" button the user could tap.

> The detailed design and acceptance documents are currently in Chinese. This is the English entry point.

---

## 1. What it can and cannot do

**Let's start with what it cannot do** — this is the baseline for an honest design:

| Cannot do | Why |
|---|---|
| Block splash ads in every app | Large apps commonly use HTTPDNS (direct IP + encrypted queries), bypassing the system resolver |
| Block ads in feeds or detail pages | That content is part of HTTPS traffic; the DNS layer cannot distinguish it |
| Block self-drawn splash screens in games | OpenGL/SurfaceView exposes no accessibility node tree |
| Block ad assets already cached on the device | If it does not use the network, DNS cannot block it |
| System-wide blocking on iOS | iOS has no cross-app automation API; only DNS configuration profiles are possible |

**What it can do:**

- Splash ads in small and medium apps that use system DNS → likely gone entirely or reduced to a very short fallback page
- The "Skip" button in large apps → tapped automatically within 6 seconds of a cold start
- Per-app control, so banking and payment apps are unaffected
- All block records are visible locally; turn the switch off at any time to restore normal behavior

For the full rationale, see [docs/feasibility.md](docs/feasibility.md).

---

## 2. Build

### Requirements

| Component | Version | Notes |
|---|---|---|
| JDK | 17 or 21 | Verified with 21 |
| Android SDK | platform 36 + build-tools 36.0.0 | `compileSdk = 36` |
| Gradle | 8.14.3 | Wrapper included |
| AGP / Kotlin | 8.10.1 / 2.1.20 | |

### Commands

```bash
# 1. Configure the SDK path (or set ANDROID_HOME)
echo "sdk.dir=/path/to/Android/Sdk" > local.properties

# 2. Generate a signing key on first use (skip if already generated)
mkdir -p keystore
keytool -genkeypair -v -keystore keystore/splashguard.jks -alias splashguard \
  -keyalg RSA -keysize 2048 -validity 10000 \
  -storepass <your-password> -keypass <your-password> -dname "CN=SplashGuard, O=Personal, C=CN"

cat > keystore.properties <<'EOF'
storeFile=keystore/splashguard.jks
storePassword=<your-password>
keyAlias=splashguard
keyPassword=<your-password>
EOF

# 3. Run unit tests
./gradlew test

# 4. Build a release APK
./gradlew assembleRelease
# Output: app/build/outputs/apk/release/app-release.apk
```

> If dependency downloads time out in mainland China, `settings.gradle.kts` already configures Aliyun mirrors, and `gradle.properties` forces IPv4. If it still fails, try `--offline` (after one successful download).

### Install

```bash
adb install -r app/build/outputs/apk/release/app-release.apk
```

Or transfer the APK to the phone and tap to install (allow "Install unknown apps").

---

## 3. First use (3 steps)

1. **Turn on the switch** → allow the system "Connection request".
   A key icon in the status bar means the tunnel is established.
2. **(Optional) Auto-skip** → on the home screen, tap "Auto-skip" and enable
   SplashGuard in system Accessibility settings.
   It only taps small controls labelled "Skip" / "Close Ad" / "Skip"; it never taps the ad itself.
3. **(Optional) Start on boot** → enabled by default on the Settings page. Add the app to the
   battery optimization allowlist, or some ROMs will kill it in the background.

**Verify it works**: open any app and check "Recent blocks" on the home screen —
a domain record means it was blocked. No record means the app uses HTTPDNS; rely on the
accessibility channel instead.

---

## 4. iOS (capability ceiling: DNS only)

iOS has no cross-app UI automation API, and it has no local VPN with custom rules that an
individual can realistically get approved (Network Extension entitlements are usually rejected
for personal use). Therefore **this project does not ship an iOS app—only a system-wide DNS
configuration profile**. See [`ios/README.md`](ios/README.md).

```bash
python ios/make_profile.py --preset adguard-doh      # generate a DoH profile
python ios/make_profile.py --preset adguard-dot      # generate a DoT variant
python ios/make_profile.py --preset nextdns --nextdns-id <ID>
python ios/check_profile.py                          # structural validation (no Mac/iPhone needed)
```

Install: AirDrop the `.mobileconfig` to the phone → Settings → General → VPN & Device Management → Install.

| iOS can | iOS cannot |
|---|---|
| Point system-wide DNS to a DoH/DoT filtering resolver | Per-app rules, local custom rules, on-device block logs |
| Clean uninstall, zero battery use, no VPN slot | Auto-tap "Skip" (Shortcuts cannot see other apps' UI) |
| Use a self-hosted DoH server for the same privacy boundary as Android | Block HTTPDNS / cached assets / self-drawn splash screens |

**Coverage expectation: noticeably lower than Android**, because Android has a second channel
(accessibility) and iOS does not. This is a platform boundary, not an implementation defect.

---

## 5. Repository layout

```text
SplashGuard/
├── README.md                  ← Chinese README
├── README.en.md               ← this file
├── docs/
│   ├── feasibility.md         Feasibility report (Android/iOS capability boundaries)
│   ├── PRD.md                 User stories + acceptance criteria
│   ├── tech-design.md         Technical design + key decisions
│   ├── testing.md             Test cases (unit + Android device + iOS profile)
│   ├── acceptance-emulator.md Emulator acceptance report (A–H + 4 fixed defects)
│   ├── evidence/              Acceptance evidence: screenshots, UI trees, memory snapshots
│   └── privacy.md             Privacy statement
├── splashtest/                Fake-splash test app for accessibility acceptance (5 screens)
├── ios/                       iOS DNS only: profiles + generator + checker
│   ├── README.md              Install, switch providers, can/cannot do
│   ├── make_profile.py        Generate .mobileconfig (reproducible UUIDs)
│   ├── check_profile.py       Structural validation, no test dependency
│   ├── SplashGuard-DNS-DoH.mobileconfig
│   └── SplashGuard-DNS-DoT.mobileconfig
├── app/src/main/
│   ├── assets/rules/splash_ads.txt   Built-in ad-domain seed list (editable)
│   ├── java/com/splashguard/app/
│   │   ├── App.kt
│   │   ├── core/       Const.kt / Prefs.kt
│   │   ├── net/        DnsPacket.kt / PacketBuilder.kt / SplashGuardVpnService.kt
│   │   ├── rules/      DomainMatcher.kt / RuleStore.kt
│   │   ├── log/        BlockLog.kt
│   │   ├── a11y/       SkipAccessibilityService.kt
│   │   ├── boot/       BootReceiver.kt
│   │   └── ui/         MainActivity / SettingsActivity / AppPickerActivity / Adapters
│   └── res/            Layouts, themes, accessibility config
└── app/src/test/       JVM unit tests for DNS packets, IP/UDP wrapping, rule matching
```

---

## 6. Key decisions

| # | Decision | Alternative | Why |
|---|---|---|---|
| 1 | **Only add DNS target addresses to the routing table** | Route all traffic | Routing all traffic requires user-space forwarding of every IP packet; battery and throughput are unacceptable. With DNS-only routing, non-DNS packets never enter the process |
| 2 | **VpnService + DNS filtering as the main channel** | Accessibility-first / Private DNS-first | The network layer is immune to app UI changes and is maintainable long-term; accessibility only fills the gaps |
| 3 | Return **0.0.0.0** by default; NXDOMAIN as an option | NXDOMAIN only | 0.0.0.0 has better compatibility; some apps hang on 0.0.0.0, so keep a switch |
| 4 | Text rules + **HashSet suffix matching** | Trie / regex | Even 200k rules are only a few MB; suffix matching is O(number of labels). A trie is over-engineering for an MVP |
| 5 | **No remote rule subscription** | Cloud-delivered rules | The privacy principle requires everything local; without the ability to maintain a subscription source, an empty shell would mislead users |
| 6 | Accessibility matches only **5 fixed labels** and limits control size | Find Skip buttons by coordinates/colors | Better to miss a click than to mis-click. Mis-clicking an ad slot is a fake click and a red line |
| 7 | Kotlin + **XML views** | Jetpack Compose | Reduce dependency size and build failure surface; the UI is simple |
| 8 | **`targetSdk = 34`** | 35/36 | Sideloading does not need Play requirements; 34 avoids mandatory edge-to-edge handling |
| 9 | Disable R8 shrinking | Enable | MVP prioritizes debuggability; crash stacks stay readable |
| 10 | Self-signed keystore; **sideload distribution** | Publish to app stores | Ad blockers cannot pass Play or Chinese app store review; the accessibility API would be considered abuse |
| 11 | iOS **profile only, no app** | iOS app with NEPacketTunnelProvider | That entitlement is usually rejected for individuals; free signing expires in 7 days, so it cannot be used daily. A profile is the only stable iOS path |
| 12 | iOS profile UUID derived from identifier via **uuid5** | Random uuid4 each time | Random UUIDs make every generation byte-different, turning Git diffs red and making it impossible to verify that a distributed file was not modified |

---

## 7. Adding rules

Built-in rules live in `app/src/main/assets/rules/splash_ads.txt`. You can also add rules in the
app under **Settings → Custom rules**; saving takes effect immediately without restarting the tunnel.

```text
# One rule per line; suffix matching
example.com          # matches example.com and a.b.example.com
||gdt.qq.com^        # AdBlock syntax is accepted
```

**Think before adding a rule**: domains such as `kwaicdn.com` serve both ads and video content.
Blocking them can break video playback. The seed list marks high-risk domains as `[High risk]`
and leaves them commented out by default.

---

## 8. Known issues

1. **HTTPDNS bypass**: large Chinese apps use Alibaba/Tencent HTTPDNS, so DNS filtering has no
   effect. This is a protocol-level limit, not a bug; the accessibility channel is the fallback.
2. **Some ROMs restrict background apps**: MIUI / ColorOS / EMUI require allowing autostart,
   or boot recovery will fail.
3. **Only one VPN at a time**: enabling this app will disconnect other VPN apps (system limitation).
4. **"Unknown app" in logs**: `getConnectionOwnerUid` cannot resolve the owner on some devices;
   this does not affect blocking itself.
5. **VPN permission may be lost after reboot**: some systems reset VPN authorization after a reboot.
   The app does not fail silently: it posts a "blocking did not resume" notification; tap it to re-authorize.
6. **On the emulator, 0.0.0.0 appears as 127.0.0.1**: Android's resolver treats 0.0.0.0 as loopback;
   the effect is a fast connection failure (faster than NXDOMAIN), which is expected.

> Emulator acceptance (API 26 / API 34, A–H) is in [docs/acceptance-emulator.md](docs/acceptance-emulator.md).
> It covers 1 P0 (all DNS broken after tunnel restart) and 2 P1 (per-app deadlock, silent boot failure).

---

## 9. Privacy

No collection, no upload, no analytics. The only outbound traffic is DNS forwarding for domains
that are not blocked (sent to the upstream you configure). See [docs/privacy.md](docs/privacy.md).

---

## 10. Roadmap

- [ ] v0.2 Per-app block statistics to help decide whether to enable a given app
- [ ] v0.3 Rule group switches (Pangle / GDT / Kuaishou separately)
- [ ] v0.4 Block TCP 53 and common DoH endpoints (currently UDP 53 only)
- [ ] v1.0 iOS profile generator (DNS over HTTPS, with limitations documented)

> The iOS profile generator was already delivered in v0.1.1; the roadmap item above is kept for
> historical context.

---

## License

MIT. See [LICENSE](LICENSE).
