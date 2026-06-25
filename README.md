# Tunnel Light

Android app that starts a local SOCKS5 proxy on **127.0.0.1:1080** and sends traffic through an Xray backend using **VLESS + REALITY + XHTTP**.

No root required. No Android VPN permission. Apps must be configured to use the local SOCKS5 proxy.

## Server

The Docker Compose backend lives in `server/xray`.

Install to a VPS from this repo:

```bash
echo 'HOST=root@your-vps-ip' > .env
make install
```

On the first run, `make install` creates `/opt/tunnel-light-xray/.env` on the VPS and stops. Fill the UUID and REALITY keys there, then run `make install` again.

Generate server values on the VPS:

```bash
cd /opt/tunnel-light-xray
docker run --rm ghcr.io/xtls/xray-core:latest uuid
docker run --rm ghcr.io/xtls/xray-core:latest x25519
```

The Android app needs:

```bash
cd /opt/tunnel-light-xray
./render-share-link.sh
```

This prints a `vless://` import link and writes it to `generated/client-link.txt`.
Any Android QR scanner that opens links can pass a scanned `vless://` QR code to
Tunnel Light.

If `qrencode` is installed on the VPS, print a terminal QR code:

```bash
./render-share-link.sh --qr
```

From a local checkout with `HOST` set in `.env`, you can also run:

```bash
make client-link
make client-qr
```

The link contains:

| Field | Source |
|---|---|
| Server | `HOST`, or `XRAY_SERVER_ADDRESS` if set |
| Port | `XRAY_SERVER_PORT` or `XRAY_PORT` |
| UUID | `XRAY_USER_UUID` |
| Public key | `XRAY_REALITY_PUBLIC_KEY` |
| SNI | `XRAY_REALITY_SERVER_NAME` |
| shortId | `XRAY_REALITY_SHORT_ID` |
| XHTTP path | `XRAY_XHTTP_PATH` |

## Android

The app includes `app/libs/libv2ray.aar`, built from `2dust/AndroidLibXrayLite`.

Build:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew :app:assembleDebug
```

Install on a connected device or emulator:

```bash
JAVA_HOME=/Library/Java/JavaVirtualMachines/zulu-17.jdk/Contents/Home ./gradlew :app:installDebug
```

## Using The Proxy

Configure any app that supports SOCKS5:

| Setting | Value |
|---|---|
| Proxy type | SOCKS5 |
| Host | 127.0.0.1 |
| Port | 1080 |

The tunnel runs as a foreground service. Tap **Stop** in the app to disconnect.

## Import Config

Scan or open a `vless://` link on Android. Tunnel Light is registered as a
handler for VLESS links, imports the config, and saves it for future starts.
