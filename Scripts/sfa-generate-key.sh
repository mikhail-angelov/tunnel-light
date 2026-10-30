#!/bin/sh
set -eu

umask 077
key_dir=${1:-"$HOME/sfa-setup"}
private_key="$key_dir/id_ed25519_sfa"
public_key="$private_key.pub"

if [ -e "$private_key" ] || [ -L "$private_key" ] || [ -e "$public_key" ] || [ -L "$public_key" ]; then
    printf 'Ключ уже существует в %s; ничего не перезаписано.\n' "$key_dir" >&2
    exit 1
fi

mkdir -p -m 700 "$key_dir"
ssh-keygen -q -t ed25519 -f "$private_key" -N '' -C 'sfa-phone'

printf 'Закрытый ключ: %s\nОткрытый ключ: %s\n\n' "$private_key" "$public_key"
cat "$public_key"
