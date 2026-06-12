# SSH Tunnel Light

A simple Android app that routes your traffic through an SSH server via a SOCKS5 proxy on **127.0.0.1:1080**.

No root required. No VPN permission. Just SSH.

---

## Install

Download the latest `app-release.apk` from the [Releases](../../releases) page and open it on your device.

> You may need to allow installation from unknown sources:  
> **Settings → Apps → Special app access → Install unknown apps**

---

## First launch

On first launch the app generates an **ED25519 key pair** stored privately on your device. The public key is shown at the bottom of the screen — you need to copy it to your server before the tunnel will connect.
<img width="728" height="808" alt="image" src="https://github.com/user-attachments/assets/e37d9cdc-07ea-4cfa-9808-e78ceee99d8b" />

---

## Setup

### 1. Copy your public key to the server

Tap **Copy key** and add the key to `~/.ssh/authorized_keys` on your SSH server:

```bash
echo "ssh-ed25519 AAAA...your-key... ssh-tunnel@android" >> ~/.ssh/authorized_keys
```

### 2. Make sure the server allows key authentication

In `/etc/ssh/sshd_config`:

```
PubkeyAuthentication yes
```

Restart SSH if you changed it: `sudo systemctl restart sshd`

### 3. Enter the server address

In the app, type your server in the field at the top:

```
user@your-server.com
```

Or with a custom port:

```
user@your-server.com:2222
```

### 4. Start the tunnel

Tap **Start**. The status line will show **Connected — SOCKS5 on 127.0.0.1:1080** when the tunnel is up.

---

## Using the proxy

Once connected, configure any app that supports SOCKS5 to use:

| Setting | Value |
|---|---|
| Proxy type | SOCKS5 |
| Host | 127.0.0.1 |
| Port | 1080 |

**Telegram:**  
Settings → Data and Storage → Proxy Settings → Add Proxy → SOCKS5  
Host `127.0.0.1`, port `1080`, no username or password.
<img width="1258" height="854" alt="image" src="https://github.com/user-attachments/assets/ee1a9d30-c423-4049-863e-ed22dd631620" />

**Firefox for Android:**  
Settings → General → Network Settings → Manual proxy → SOCKS5, host `127.0.0.1`, port `1080`.

---

## The tunnel keeps running in the background

The tunnel runs as a foreground service — you will see a persistent notification while it is active. Pressing the back button or switching apps does **not** stop the tunnel. Tap **Stop** in the app to disconnect.

---

## Regenerating your key

If you need a new key pair (e.g. your device was lost), tap **Regenerate key** and confirm. A new key will be generated and shown. You must add the new public key to `authorized_keys` on your server — the old key will stop working.

---

## Troubleshooting

**Status shows "Error: …"**  
- Check that the public key is in `~/.ssh/authorized_keys` on the server  
- Make sure `PubkeyAuthentication yes` is set in `sshd_config`  
- Verify the server address and port are correct  

**Tunnel connects but traffic doesn't route**  
- Confirm the app you're using is actually configured to use the SOCKS5 proxy  
- Some apps ignore system proxy settings — you need per-app proxy configuration  

**Tunnel stops after a few minutes**  
- Open the app and tap **Open Settings** when prompted about battery optimization, then select **Unrestricted** for this app  
