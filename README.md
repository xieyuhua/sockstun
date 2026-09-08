# SocksTun

A simple and lightweight VPN proxy client for Android. It embeds [mihomo](https://github.com/MetaCubeX/mihomo) (the Clash.Meta core) so a single `clash.yml` subscription unlocks the **full multi-protocol stack** — vmess / vless / trojan / shadowsocks / shadowsocksr / hysteria2 / tuic / socks5 / wireguard and more. The TUN is owned by mihomo; the app only supplies the VPN file descriptor and a `protect()` callback, so routing, DNS hijacking and rule-providers all run inside the core.


## Features

* **Multi-protocol**: any node type supported by mihomo, straight from your subscription.
* Redirect TCP connections.
* Redirect UDP packets.
* Simple username/password authentication.
* Specifying DNS addresses.
* IPv4/IPv6 dual stack.
* Global/per-App modes.
* Traffic statistics (live rate, session and total usage, per-app usage).
* Clash subscription: import a remote `clash.yml` (plain or base64), list **all** proxy nodes, test latency, and pick the one to use.
* Routing is handled by the subscription's own `rules` / `rule-providers` / `proxy-groups` inside mihomo (the app pushes all traffic into the TUN and lets the core decide proxy vs direct).
* Multiple profiles (up to 13), Quick Settings tile, start on boot.

## Documents

* [使用教程 (Usage guide, 中文)](docs/使用教程.md)
* [启动与架构说明 (Startup & architecture, 中文)](docs/启动与架构说明.md)

## How to Build

Fork this project and create a new release, or build manually:

```bash
git clone --recursive https://github.com/xieyuhua/sockstun
cd sockstun
gradle assembleDebug
```

> **Note — embedded mihomo core.** The proxy engine is the prebuilt
> [`libmihomo-android`](https://github.com/oviron/libmihomo-android) AAR
> (mihomo v1.19.30, pinned to `v0.3.3` in `app/build.gradle`). It is fetched
> from GitHub Releases on the first build (or after a `clean`), so an internet
> connection is required at build time. The AAR ships native libraries for
> `arm64-v8a` and `armeabi-v7a`; `abiFilters` in `app/build.gradle` is aligned
> to those two ABIs. The embedded core is GPL-3.0 licensed (statically linked
> Go code) — distribute accordingly.

docker 

```
docker pull  mingc/android-build-box

docker run --rm \
  -v "$PWD":/project \
  -v "$HOME/.gradle-cache":/root/.gradle \
  -v "$HOME/android-ndk":/opt/android-sdk/ndk \
  -e JAVA_HOME=/usr/lib/jvm/java-17-openjdk-amd64 \
  -e ANDROID_SDK_ROOT=/opt/android-sdk \
  mingc/android-build-box \
  bash -lc 'cd /project && ./gradlew assembleDebug --warning-mode all --no-daemon'
```

## Socks5 Server

### UDP relay over TCP

```bash
git clone --recursive https://github.com/heiher/hev-socks5-server
cd hev-socks5-server
make

hev-socks5-server conf.yml
```

```yaml
main:
  workers: 4
  port: 1080
  listen-address: '::'

misc:
  limit-nofile: 65535
```

### UDP relay over UDP

Any socks5 server that implements the CONNECT and UDP-ASSOCIATE methods of RFC1928.