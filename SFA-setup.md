# SFA через SSH: настройка телефона и сервера

Проверенная схема для **sing-box for Android (SFA) 1.14.4**: телефон создаёт Android VPN, передаёт TCP и UDP внутри SSH, а sing-box на сервере выпускает трафик в интернет. UDP передаётся как UDP-over-TCP. Для этого **не нужно менять SFA или ядро sing-box**. На сервере нужен Docker с `docker compose` и работающий SSH. Команды рассчитаны на Ubuntu 22.04; на 20.04 они применимы при уже установленном Docker Compose, но для новой установки Docker официально поддерживает Ubuntu 22.04 и новее. Проверьте **обе** команды: `docker --version` и `docker compose version`. Если отсутствует только Compose, в Ubuntu 22.04 установите `sudo apt update && sudo apt install docker-compose-v2`; если отсутствует сам Docker, следуйте [официальной инструкции для Ubuntu](https://docs.docker.com/engine/install/ubuntu/) на поддерживаемой версии системы. `docker compose -v` не заменяет проверку `docker compose version`: при отсутствующем плагине Docker может вывести версию самого Docker.

```text
Телефон (SFA TUN) → SSH → 127.0.0.1:28761 на сервере → sing-box SOCKS → интернет
```

Ниже команды для одного телефона и отдельного пользователя `sfa`. Для каждого следующего телефона создайте отдельную пару ключей и профиль; открытые ключи можно добавить в тот же `authorized_keys`.

## 1. Создать ключ на компьютере

Из корня этого репозитория на доверенном компьютере (macOS или Linux):

```sh
./Scripts/sfa-generate-key.sh
```

Скрипт создаёт каталог `~/sfa-setup` вне репозитория и показывает открытый ключ. `~/sfa-setup/id_ed25519_sfa.pub` — открытый ключ для сервера. `~/sfa-setup/id_ed25519_sfa` — закрытый ключ для профиля телефона; никому его не отправляйте. Скрипт не перезаписывает существующий ключ. Для другого телефона укажите отдельный каталог: `./Scripts/sfa-generate-key.sh "$HOME/sfa-phone-2"`. Ключ создаётся без парольной фразы, поскольку профиль должен подключаться без ручного ввода после запуска VPN. Храните файл и резервную копию профиля в защищённом месте.

## 2. Запустить серверный sing-box через Docker Compose

Оставьте действующий административный SSH-доступ открытым, пока не проверите нового пользователя. На сервере создайте каталог и конфигурацию:

```sh
sudo install -d -m 755 /opt/sfa-uot
sudo tee /opt/sfa-uot/config.json >/dev/null <<'JSON'
{
  "log": { "level": "info" },
  "inbounds": [
    {
      "type": "socks",
      "tag": "loopback-socks",
      "listen": "127.0.0.1",
      "listen_port": 28761
    }
  ],
  "outbounds": [{ "type": "direct", "tag": "direct" }],
  "route": {
    "rules": [{ "ip_is_private": true, "action": "reject" }],
    "final": "direct"
  }
}
JSON
sudo chmod 644 /opt/sfa-uot/config.json
```

Этот SOCKS не имеет собственного пароля: доступ к нему ограничивают адрес `127.0.0.1` и правила SSH ниже. Правило `ip_is_private` отклоняет обращения к непубличным IP-адресам. При выдаче доменных имён дополнительно проверьте, что они не разрешаются во внутренние адреса вашей сети; для строгой сетевой изоляции нужны правила на уровне сервера.

Создайте `/opt/sfa-uot/compose.yaml`:

```yaml
services:
  sfa-uot:
    image: ghcr.io/sagernet/sing-box:v1.14.4
    restart: unless-stopped
    network_mode: host
    user: "65534:65534"
    read_only: true
    cap_drop: [ALL]
    security_opt: ["no-new-privileges:true"]
    pids_limit: 128
    mem_limit: 256m
    ulimits:
      nofile:
        soft: 4096
        hard: 4096
    volumes:
      - /opt/sfa-uot/config.json:/etc/sing-box/config.json:ro
    command: ["run", "-c", "/etc/sing-box/config.json"]
    logging:
      options:
        max-size: "10m"
        max-file: "3"
```

`network_mode: host` нужен здесь, чтобы sing-box слушал именно серверный `127.0.0.1`, без опубликованного Docker-порта. Контейнер делит сетевое пространство хоста, поэтому конфигурация запускает его без root и дополнительных Linux capabilities. Не добавляйте `ports:` и не меняйте `listen` на `0.0.0.0`.

```sh
cd /opt/sfa-uot
sudo docker compose config -q
sudo docker compose run --rm --no-deps sfa-uot check -c /etc/sing-box/config.json
sudo docker compose up -d
sudo docker compose ps
sudo ss -lntp '( sport = :28761 )'
```

В последней команде ожидается **только `127.0.0.1:28761`**. При необходимости смотрите логи: `sudo docker compose logs --tail=100 sfa-uot`. Дополнительный публичный порт для этой схемы не открывается.

## 3. Создать ограниченного SSH-пользователя

На сервере:

```sh
sudo useradd --create-home --shell /usr/sbin/nologin sfa
sudo install -d -m 700 -o sfa -g sfa /home/sfa/.ssh
```

С компьютера передайте **только открытый ключ** через ваш существующий административный SSH-доступ:

```sh
scp "$HOME/sfa-setup/id_ed25519_sfa.pub" ADMIN@SERVER_IP:/tmp/sfa-phone-1.pub
```

На сервере установите его для нового пользователя:

```sh
sudo install -m 600 -o sfa -g sfa /tmp/sfa-phone-1.pub /home/sfa/.ssh/authorized_keys
rm /tmp/sfa-phone-1.pub
```

Вместо `ADMIN` и `SERVER_IP` укажите существующего администратора и публичный IP сервера. Команда `install` рассчитана на **нового** пользователя `sfa`; если `authorized_keys` уже существует, добавьте ключ новой строкой, не перезаписывая файл.

**Один раз** добавьте в конец `/etc/ssh/sshd_config` блок для `sfa` (после всех общих настроек и `Include`):

```text
Match User sfa
    PubkeyAuthentication yes
    PasswordAuthentication no
    KbdInteractiveAuthentication no
    AuthenticationMethods publickey
    AllowTcpForwarding local
    PermitOpen 127.0.0.1:28761
    PermitListen none
    MaxSessions 0
    PermitTTY no
    X11Forwarding no
    AllowAgentForwarding no
    AllowStreamLocalForwarding no
    PermitTunnel no
```

`MaxSessions 0` запрещает shell/SFTP-сессии, сохраняя переадресацию; `PermitOpen` разрешает ей только серверный SOCKS. Не помещайте этот `Match` в файл из `sshd_config.d`, если основной `sshd_config` подключает такие файлы перед общими настройками: следующие директивы могут оказаться внутри блока `Match`.

Проверьте синтаксис и эффективные настройки **до перезагрузки SSH**:

```sh
sudo sshd -t
sudo sshd -T -C user=sfa,host=localhost,addr=127.0.0.1 | grep -E '^(allowtcpforwarding|permitopen|maxsessions|passwordauthentication) '
sudo systemctl reload ssh
```

Ожидаются `allowtcpforwarding local`, `permitopen 127.0.0.1:28761`, `maxsessions 0`, `passwordauthentication no`. Не закрывайте административную SSH-сессию до успешного подключения SFA.

## 4. Получить и проверить ключ **сервера**

Ключ сервера отличается от созданного в шаге 1 пользовательского ключа. На сервере через доверенный административный доступ получите **открытый** host key. Если Ed25519-ключа нет, используйте RSA:

```sh
if [ -f /etc/ssh/ssh_host_ed25519_key.pub ]; then
    host_key_file=/etc/ssh/ssh_host_ed25519_key.pub
else
    host_key_file=/etc/ssh/ssh_host_rsa_key.pub
fi
sudo cat "$host_key_file"
sudo ssh-keygen -lf "$host_key_file"
```

Сохраните показанную строку `ssh-ed25519 AAAA...` или `ssh-rsa AAAA...` на компьютере в `~/sfa-setup/ssh_host_key.pub`; сравните отпечаток `ssh-keygen -lf "$HOME/sfa-setup/ssh_host_key.pub"` с выводом на сервере. На текущем сервере `77.81.138.144` используется RSA host key; именно его мы закрепили в работающем профиле. Не копируйте файл без `.pub`. **Не оставляйте `host_key` пустым**: sing-box тогда принимает любой ключ сервера.

## 5. Собрать профиль SFA на компьютере

В `~/sfa-setup` должны быть `id_ed25519_sfa` и проверенный `ssh_host_key.pub`. Из корня репозитория укажите публичный IP сервера:

```sh
./Scripts/sfa-build-profile.sh 203.0.113.10 --port 22
```

Замените пример на **публичный IP** сервера, чтобы разрешение его имени не зависело от ещё не поднятого VPN. Скрипт создаёт `~/sfa-setup/sfa-ssh.json` с правами `600` и не перезаписывает существующий файл. Для другого каталога добавьте `--dir "$HOME/sfa-phone-2"`; для другого SSH-пользователя — `--user ИМЯ`. Если для следующих телефонов создаёте отдельные ключи, повторите шаги 1, 3 и 5. `172.18.0.1/30` — адрес внутреннего TUN-интерфейса телефона, а не адрес Docker-сети.

## 6. Импортировать и проверить на телефоне

1. Передайте `~/sfa-setup/sfa-ssh.json` на телефон по USB или другим доверенным способом. Не публикуйте его и не пересылайте в чат: **JSON содержит закрытый SSH-ключ**.
2. В SFA выберите **Профили → Добавить профиль → Импорт из файла**, выберите JSON и запустите профиль. Разрешите Android VPN-доступ.
3. Проверьте внешний IP в браузере и убедитесь, что это IP сервера. Проверьте приложение с UDP-трафиком и логи SFA; отдельный UDP-запрос с телефона в нашем прототипе ещё не проверен.
4. После успешного импорта удалите исходный `sfa-ssh.json` из общедоступной папки телефона (например, `Downloads`). **Это не удаляет уже импортированный профиль**: в нашем тесте SFA запускала его после удаления исходного файла. Закрытый ключ остаётся внутри профиля SFA; экспорт или пересылка профиля снова раскрывает его.
5. Проверьте переподключение после смены Wi-Fi/мобильной сети и перезагрузки телефона. Если нельзя допустить прямой трафик при отключённом VPN, включите в настройках Android постоянный VPN и блокировку соединений без VPN.

При отключённом серверном контейнере профиль не сможет передавать TCP и UDP через SSH; проверьте это отдельно. UDP-over-TCP подходит для обычного трафика, но может добавлять задержку, особенно для игр и звонков.

## Источники

- [sing-box: Docker](https://sing-box.sagernet.org/installation/docker/), [SSH outbound](https://sing-box.sagernet.org/configuration/outbound/ssh/), [SOCKS outbound и UDP-over-TCP](https://sing-box.sagernet.org/configuration/outbound/socks/)
- [OpenSSH: `Match`, `PermitOpen`, `MaxSessions`](https://man.openbsd.org/sshd_config)
- [Docker: host networking](https://docs.docker.com/compose/how-tos/networking/), [публикация портов](https://docs.docker.com/engine/network/port-publishing/)
