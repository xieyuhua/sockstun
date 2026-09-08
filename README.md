# SocksTun

A simple and lightweight VPN over socks5 proxy for Android. It is based on a high-performance and low-overhead [tun2socks](https://github.com/heiher/hev-socks5-tunnel).


## Features

* Redirect TCP connections.
* Redirect UDP packets. (Fullcone NAT, UDP-in-UDP and UDP-in-TCP [^1])
* Simple username/password authentication.
* Specifying DNS addresses.
* IPv4/IPv6 dual stack.
* Global/per-App modes.
* Traffic statistics (live rate, session and total usage, per-app usage).
* Clash subscription: import a remote clash.yml, pick a SOCKS5 node, test latency.
* Routing rules by domain / IP / CIDR: proxy or direct.
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