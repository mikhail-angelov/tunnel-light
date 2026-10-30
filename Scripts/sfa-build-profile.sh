#!/bin/sh
set -eu

usage() {
    printf 'Использование: %s SERVER_IPV4 [--port PORT] [--user USER] [--dir KEY_DIR]\n' "$0" >&2
    exit 2
}

[ "$#" -ge 1 ] || usage
server_ip=$1
shift
port=22
ssh_user=sfa
key_dir=$HOME/sfa-setup

while [ "$#" -gt 0 ]; do
    [ "$#" -ge 2 ] || usage
    case $1 in
        --port) port=$2 ;;
        --user) ssh_user=$2 ;;
        --dir) key_dir=$2 ;;
        *) usage ;;
    esac
    shift 2
done

if ! printf '%s\n' "$server_ip" | awk -F. '
    NF != 4 { exit 1 }
    { for (i = 1; i <= 4; i++)
        if ($i !~ /^[0-9]+$/ || length($i) > 3 || $i > 255) exit 1 }
'; then
    printf 'Укажите IPv4-адрес сервера, без имени хоста.\n' >&2
    exit 1
fi
if ! awk -v p="$port" 'BEGIN { exit !(p ~ /^[0-9]+$/ && p >= 1 && p <= 65535) }'; then
    printf 'SSH-порт должен быть от 1 до 65535.\n' >&2
    exit 1
fi
case $ssh_user in
    ''|*[!a-zA-Z0-9_.-]*) printf 'Недопустимое имя SSH-пользователя.\n' >&2; exit 1 ;;
esac

private_key=$key_dir/id_ed25519_sfa
host_key_file=$key_dir/ssh_host_key.pub
output=$key_dir/sfa-ssh.json
for file in "$private_key" "$host_key_file"; do
    if [ ! -r "$file" ]; then
        printf 'Не удалось прочитать файл: %s\n' "$file" >&2
        exit 1
    fi
done
if [ -e "$output" ] || [ -L "$output" ]; then
    printf 'Профиль уже существует: %s; ничего не перезаписано.\n' "$output" >&2
    exit 1
fi

if [ "$(head -n 1 "$private_key")" != '-----BEGIN OPENSSH PRIVATE KEY-----' ] ||
   [ "$(tail -n 1 "$private_key")" != '-----END OPENSSH PRIVATE KEY-----' ] ||
   LC_ALL=C grep -q '[^A-Za-z0-9+/= -]' "$private_key"; then
    printf 'Неверный формат закрытого OpenSSH-ключа: %s\n' "$private_key" >&2
    exit 1
fi
if ! ssh-keygen -lf "$host_key_file" >/dev/null 2>&1; then
    printf 'Неверный открытый ключ сервера: %s\n' "$host_key_file" >&2
    exit 1
fi

private_key_json=$(awk '{ printf "%s\\n", $0 }' "$private_key")
host_key=$(awk 'NR == 1 { print $1 " " $2 }' "$host_key_file")

umask 077
set -C
cat > "$output" <<JSON
{
  "log": { "level": "info" },
  "inbounds": [{
    "type": "tun", "tag": "tun-in", "address": ["172.18.0.1/30"],
    "auto_route": true, "dns_mode": "hijack"
  }],
  "outbounds": [
    {
      "type": "ssh", "tag": "ssh-out", "server": "$server_ip",
      "server_port": $port, "user": "$ssh_user",
      "private_key": "$private_key_json",
      "host_key": ["$host_key"]
    },
    {
      "type": "socks", "tag": "proxy", "server": "127.0.0.1",
      "server_port": 28761, "version": "5", "udp_over_tcp": true,
      "detour": "ssh-out"
    }
  ],
  "dns": {
    "servers": [{
      "type": "tls", "tag": "dns-over-ssh", "server": "1.1.1.1",
      "server_port": 853, "detour": "proxy"
    }],
    "final": "dns-over-ssh"
  },
  "route": { "final": "proxy", "auto_detect_interface": true }
}
JSON

printf 'Создан профиль: %s\n' "$output"
