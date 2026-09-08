# SocksTun

A simple and lightweight VPN over socks5 proxy for Android. It is based on a high-performance and low-overhead [tun2socks](https://github.com/heiher/hev-socks5-tunnel).


## Features

* Redirect TCP connections.
* Redirect UDP packets. (Fullcone NAT, UDP-in-UDP and UDP-in-TCP [^1])
* Simple username/password authentication.
* Specifying DNS addresses.
* IPv4/IPv6 dual stack.
* Global/per-App modes.

## How to Build

Fork this project and create a new release, or build manually:

```bash
git clone --recursive https://github.com/xieyuhua/sockstun
cd sockstun
gradle assembleDebug
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