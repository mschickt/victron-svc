# victron-svc — Installation & Operation Guide

Complete setup for the Victron BLE service: OS packages, BlueZ configuration,
device credentials, and both deployment paths (Docker and native systemd).

The service reads Victron "Instant Readout" BLE advertisements, decrypts them
(AES-CTR with each device's advertisement key), decodes the Victron bit layout,
and exposes the values over REST + Prometheus.

> **Platform:** Requires **Linux + BlueZ** at runtime (it talks to BlueZ over
> D-Bus). It runs on a Raspberry Pi (Raspberry Pi OS 64-bit) or any Linux host
> with a BLE adapter. It does **not** run on macOS — only build/tests work there.

---

## 1. Hardware / prerequisites

- Raspberry Pi (3/4/5) or Linux host with a Bluetooth LE adapter (`hci0`).
- Victron devices (MPPT, SmartShunt, Orion-Tr Smart) with **Instant Readout
  enabled** in the VictronConnect app (Settings → Product info → Instant readout).
- For each device, its **Advertisement Key**:
  VictronConnect → *Device* → ⋮ → *Product info* → *Advertisement Key* (32 hex chars).

---

## 2. System packages (apt)

Run on the host (Raspberry Pi OS / Debian / Ubuntu).

### 2.1 BlueZ + Bluetooth stack (always required)

```bash
sudo apt update
sudo apt install -y \
  bluez \
  bluetooth \
  dbus \
  rfkill \
  libcap2-bin
```

| Package        | Why |
|----------------|-----|
| `bluez`        | BlueZ stack + `bluetoothd` (the D-Bus service the app talks to) |
| `bluetooth`    | pulls in `bluetooth.service` |
| `dbus`         | system bus; the app reaches BlueZ via `/var/run/dbus/system_bus_socket` |
| `rfkill`       | un-block the radio (see §3) |
| `libcap2-bin`  | `setcap`/capability tooling for non-root BLE access |

### 2.2 Path A — Docker runtime (recommended)

```bash
# Docker Engine + Compose plugin (official convenience script)
curl -fsSL https://get.docker.com | sudo sh
sudo usermod -aG docker "$USER"      # log out/in afterwards so the group applies
```

This installs `docker-ce`, `docker-ce-cli`, `containerd.io`,
`docker-buildx-plugin` and `docker-compose-plugin`. The JDK/Maven build runs
**inside** the image, so you do not install Java on the host for this path.

### 2.3 Path B — Native build/run (no Docker)

Install Azul Zulu JDK 25 from Azul's apt repo, plus Maven:

```bash
sudo apt install -y gnupg ca-certificates curl

# Azul Zulu apt repository
curl -fsSL https://repos.azul.com/azul-repo.key \
  | sudo gpg --dearmor -o /usr/share/keyrings/azul.gpg
echo "deb [signed-by=/usr/share/keyrings/azul.gpg] https://repos.azul.com/zulu/deb stable main" \
  | sudo tee /etc/apt/sources.list.d/zulu.list
sudo apt update
sudo apt install -y zulu25-jdk maven
```

Verify Java 25 is active for the build:

```bash
java -version          # should report 25
export JAVA_HOME=/usr/lib/jvm/zulu25   # adjust if apt installed elsewhere
```

---

## 3. BlueZ configuration (critical — common failure point)

The BLE adapter must be **un-blocked** and **powered on**, or the service fails
at startup with `DBusExecutionException: Resource Not Ready`.

### 3.1 Un-block the radio (rfkill)

```bash
rfkill list bluetooth            # check: "Soft blocked: yes" is the problem
sudo rfkill unblock bluetooth
```

The un-block state is persisted by `systemd-rfkill` across reboots.

### 3.2 Auto-power the adapter on boot

Enable `AutoEnable` so BlueZ powers the controller up automatically:

```bash
sudo sed -i 's/^#\?AutoEnable=.*/AutoEnable=true/' /etc/bluetooth/main.conf
sudo systemctl restart bluetooth
```

Confirm `[Policy]` now contains `AutoEnable=true`.

### 3.3 Verify

```bash
sudo systemctl enable --now bluetooth
hciconfig hci0           # should show: UP RUNNING
rfkill list bluetooth    # should show: Soft blocked: no
```

> The service also calls `setPowered(true)` on the adapter at scan start, so a
> merely *powered-down* adapter self-heals. But an **rfkill soft-block is
> kernel-level** and can only be cleared with `rfkill unblock` — §3.1 is
> mandatory.

---

## 4. Device configuration

Devices are configured under `victron.devices` in `application.yml`. **Do not
put real advertisement keys in the committed `application.yml`** — it ships
placeholders. Use one of the two methods below.

### 4.1 Method 1 — `application-local.yml` (gitignored, baked into the build)

Create `src/main/resources/application-local.yml`:

```yaml
victron:
  devices:
    - mac: "F0:EF:86:EA:AA:43"
      name: "N08LC"
      type: MPPT
      advertisement-key: "<32-hex-key>"
    - mac: "59:F3:A5:6E:67:E1"
      name: "N04CA"
      type: SMART_SHUNT
      advertisement-key: "<32-hex-key>"
    - mac: "64:0C:03:21:F9:D2"
      name: "N028Z"
      type: ORION_TR
      advertisement-key: "<32-hex-key>"
```

This file is loaded **only under the `local` Quarkus profile**, so you must set
`QUARKUS_PROFILE=local` (already set in `docker-compose.yml`; for native, put it
in the env file — see §6). Rebuild after editing.

> `type` is informational only — the decoder is selected by each
> advertisement's own record type, not by this field.
>
> Find each device's MAC by running a scan and matching the advertised name in
> VictronConnect: `bluetoothctl scan le` then `bluetoothctl devices`.

### 4.2 Method 2 — environment variables (no rebuild)

Indexed env vars override the list and need no `local` profile:

```bash
VICTRON_DEVICES_0_MAC=F0:EF:86:EA:AA:43
VICTRON_DEVICES_0_NAME=N08LC
VICTRON_DEVICES_0_TYPE=MPPT
VICTRON_DEVICES_0_ADVERTISEMENT_KEY=<32-hex-key>
VICTRON_DEVICES_1_MAC=59:F3:A5:6E:67:E1
# ... _1_, _2_ for the remaining devices
```

In Docker, add these under `environment:` in `docker-compose.yml`.
For native, put them in `/etc/victron-svc.env` (§6).

---

## 5. Deploy — Docker (Path A)

From the repo root:

```bash
docker compose up -d --build          # or: ./start.sh
docker compose logs -f victron-svc
```

What the compose file does (`docker-compose.yml`):
- `network_mode: host` — so it reaches BlueZ and Prometheus via `localhost`.
- mounts `/var/run/dbus` — D-Bus access to BlueZ on the host.
- `cap_add: NET_ADMIN, NET_RAW` — BLE without running as root.
- `QUARKUS_PROFILE: local` — activates `application-local.yml`.
- mounts `./data/db` — SQLite reading history persists on the host.

Endpoints:
- Service / REST: `http://localhost:8090`
- Prometheus metrics: `http://localhost:8090/q/metrics`
- Health: `http://localhost:8090/q/health`

The full monitoring stack (Prometheus + Grafana) is also defined — see §8.

---

## 6. Deploy — native systemd (Path B)

Build, install, and run as a hardened systemd service (no Docker).

```bash
# 1. Build (device creds via application-local.yml are baked in here)
mvn -DskipTests package

# 2. Install artifacts
sudo mkdir -p /opt/victron-svc
sudo cp -r target/quarkus-app/* /opt/victron-svc/

# 3. Service account in the bluetooth group
sudo useradd -r -G bluetooth victron 2>/dev/null || true

# 4. systemd unit
sudo cp deploy/victron-svc.service /etc/systemd/system/

# 5. Environment file (profile + any env-based device creds)
sudo tee /etc/victron-svc.env >/dev/null <<'EOF'
QUARKUS_PROFILE=local
VICTRON_DB_PATH=/opt/victron-svc/data/victron.db
# Optional: device creds via env instead of application-local.yml
# VICTRON_DEVICES_0_MAC=F0:EF:86:EA:AA:43
# VICTRON_DEVICES_0_ADVERTISEMENT_KEY=<32-hex-key>
EOF

# 6. Enable + start
sudo systemctl daemon-reload
sudo systemctl enable --now victron-svc
journalctl -u victron-svc -f
```

The unit (`deploy/victron-svc.service`) runs as user `victron`, group
`bluetooth`, grants `CAP_NET_ADMIN`/`CAP_NET_RAW` (no root), and orders itself
`After=bluetooth.service`. It reads `/etc/victron-svc.env`.

---

## 7. Scanning (auto-start) and the scan API

Scanning **starts automatically at boot** (`victron.ble.auto-start: true`) and
polls the BlueZ cache for new advertisement values every
`victron.ble.scan-interval` (default `2s`). It processes **only the configured
devices** — any other BLE device the adapter sees is ignored. Set
`auto-start: false` (or `VICTRON_BLE_AUTO_START=false`) to require manual start.

| Method & Path                     | Effect |
|-----------------------------------|--------|
| `POST /api/victron/scan/start`    | enable scanning + start BLE discovery |
| `POST /api/victron/scan/stop`     | disable scanning + stop discovery |
| `GET  /api/victron/scan`          | `{ "scanning": bool, "discoveryActive": bool }` |
| `GET  /api/victron/devices`       | configured devices + per-device `lastSeen` / `secondsSinceLastSeen` |

```bash
curl http://localhost:8090/api/victron/scan          # is it running?
curl http://localhost:8090/api/victron/devices | jq  # last-seen per device
curl http://localhost:8090/api/victron/dashboard | jq
```

Each device's last-seen time is also exported to Prometheus as
`victron_last_seen_epoch_seconds{mac,name}` — alert on
`time() - victron_last_seen_epoch_seconds > 60` to catch a device that stopped
broadcasting.

### Data endpoints

| Method & Path                   | Description |
|---------------------------------|-------------|
| `GET /api/victron/dashboard`    | all devices in one call |
| `GET /api/victron/mppt`         | all MPPT chargers |
| `GET /api/victron/mppt/{mac}`   | one MPPT |
| `GET /api/victron/shunt`        | all SmartShunts |
| `GET /api/victron/shunt/{mac}`  | one SmartShunt |
| `GET /api/victron/orion`        | all Orion-Tr converters |
| `GET /api/victron/orion/{mac}`  | one Orion |
| `GET /q/metrics`                | Prometheus metrics (Micrometer) |
| `GET /q/health`                 | health check |

Every decoded advertisement is also appended to a local SQLite DB
(`data/db/victron.db`, override `VICTRON_DB_PATH`): tables `mppt_reading`,
`shunt_reading`, `orion_reading`, one row per BLE frame.

---

## 8. Monitoring stack (Prometheus + Grafana)

`docker-compose.yml` plus `deploy/` provide a ready stack:

- `deploy/prometheus.yml` — scrapes `localhost:8090/q/metrics` every 15s.
- `deploy/grafana/provisioning/` — auto-provisions the Prometheus datasource
  and dashboard provider.
- `deploy/grafana/dashboards/victron.json` — starter dashboard (battery V/A,
  SoC, solar power, Orion output).

All three run `network_mode: host`, so they reach each other via `localhost`.

---

## 9. Configuration reference

| Key (`application.yml`)      | Env var                         | Default | Meaning |
|------------------------------|---------------------------------|---------|---------|
| `victron.ble.adapter`        | `VICTRON_BLE_ADAPTER`           | `hci0`  | BlueZ adapter name |
| `victron.ble.scan-interval`  | `VICTRON_BLE_SCAN_INTERVAL`     | `2s`    | poll interval for new advertisement values |
| `victron.ble.auto-start`     | `VICTRON_BLE_AUTO_START`        | `true`  | start scanning automatically at boot |
| `victron.devices[n].mac`     | `VICTRON_DEVICES_n_MAC`         | —       | device MAC |
| `victron.devices[n].name`    | `VICTRON_DEVICES_n_NAME`        | —       | label |
| `victron.devices[n].type`    | `VICTRON_DEVICES_n_TYPE`        | —       | `MPPT` / `SMART_SHUNT` / `ORION_TR` (informational) |
| `victron.devices[n].advertisement-key` | `VICTRON_DEVICES_n_ADVERTISEMENT_KEY` | — | 32-hex AES key |
| —                            | `VICTRON_DB_PATH`               | `./data/db/victron.db` | SQLite path |
| —                            | `QUARKUS_PROFILE`               | `prod`  | set to `local` to load `application-local.yml` |

---

## 10. Troubleshooting

| Symptom | Cause / fix |
|---------|-------------|
| `DBusExecutionException: Resource Not Ready` at startup | Adapter blocked/down → `sudo rfkill unblock bluetooth`, `sudo hciconfig hci0 up`, set `AutoEnable=true` (§3). |
| `hci0 ... DOWN` | `sudo hciconfig hci0 up`; check `rfkill list`. |
| `Bluetooth Adapter 'hci0' nicht gefunden` | Wrong adapter name or BlueZ not running → `systemctl status bluetooth`, `hciconfig -a`. |
| Dashboard stays empty | Scanning not started (`POST /api/victron/scan/start`) **or** placeholder MAC/keys still configured (§4). |
| `Entschlüsselung fehlgeschlagen` | Wrong advertisement key for that MAC. |
| Container `unhealthy` | Check `docker compose logs victron-svc`; usually BlueZ not ready (§3). |
| Permission denied on D-Bus (native) | User not in `bluetooth` group / missing `CAP_NET_*` — use the provided systemd unit. |

Useful checks:

```bash
hciconfig -a                      # adapter state
rfkill list                       # block state
bluetoothctl devices             # what the adapter currently sees
docker compose logs -f victron-svc
journalctl -u victron-svc -f      # native
```
