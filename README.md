# victron-svc

Quarkus-basierter Service, der Victron-Geräte (MPPT, SmartShunt, Orion-Tr Smart)
per **Bluetooth Low Energy** ausliest und die Messwerte über eine **REST-API**
bereitstellt. Liest die verschlüsselten BLE-Advertisements ("Instant Readout")
aus, entschlüsselt sie via AES-CTR mit dem Advertisement Key und dekodiert das
Victron-Bit-Layout.

> 📖 **Vollständige Installationsanleitung** (apt-Pakete, BlueZ-Konfiguration,
> Docker- und systemd-Deployment, Scan-API, Troubleshooting):
> **[`docs/INSTALL.md`](docs/INSTALL.md)**

## Voraussetzungen

- Azul Zulu JDK 25 (oder kompatible OpenJDK 25 Distribution)
- Linux mit BlueZ (`bluez`, `bluetooth`) — z. B. Raspberry Pi OS 64-bit
- Victron-Geräte mit aktiviertem "Instant Readout" (in VictronConnect einschalten)

## Konfiguration

Trage MAC-Adressen und Advertisement Keys in `src/main/resources/application.yml` ein.
Den Advertisement Key bekommst du in VictronConnect:
**Gerät → ⋮ → Produktinfo → Advertisement Key** (Hex-String).

> Für echte Keys nutze lieber `application-local.yml` oder Umgebungsvariablen,
> damit sie nicht im Git landen (sind in `.gitignore`).

Beispiel via Env:

```
VICTRON_DEVICES_0_MAC=...
VICTRON_DEVICES_0_ADVERTISEMENT_KEY=...
```

## Start

### Lokal / Dev

```bash
mvn quarkus:dev
mvn package && java -jar target/quarkus-app/quarkus-run.jar
```

### Raspbian / Raspberry Pi OS 64-bit

```bash
# Zulu 25 (siehe https://www.azul.com/downloads/?package=jdk)
sudo apt install bluez bluetooth zulu25-jdk
mvn -DskipTests package
sudo mkdir -p /opt/victron-svc
sudo cp -r target/quarkus-app/* /opt/victron-svc/
sudo useradd -r -G bluetooth victron 2>/dev/null || true
sudo cp deploy/victron-svc.service /etc/systemd/system/
# Echte Keys in /etc/victron-svc.env (KEY=VALUE pro Zeile)
sudo systemctl daemon-reload
sudo systemctl enable --now victron-svc
journalctl -u victron-svc -f
```

### Docker

```bash
docker compose up -d --build
```

- victron-svc:  `http://localhost:8090`
- Prometheus Metrics: `http://localhost:8090/q/metrics`
- SQLite DB:    `./data/db/victron.db`

Der Container nutzt `network_mode: host` und mountet den D-Bus-Socket, damit
BlueZ auf `hci0` erreichbar ist. Prometheus und Grafana laufen separat
(z. B. in einer eigenen Compose-Datei) und scrapen `localhost:8090/q/metrics`
— Beispiel-Configs liegen unter `deploy/prometheus.yml` und
`deploy/grafana/`.

## API

> ℹ️ **Scanning startet automatisch** (`victron.ble.auto-start: true`) und
> verarbeitet **nur konfigurierte Geräte**. Mit `POST /api/victron/scan/stop`
> abschaltbar.

| Methode & Pfad                  | Beschreibung                       |
|---------------------------------|------------------------------------|
| `GET /api/victron/devices`      | Konfigurierte Geräte + Last-Seen   |
| `POST /api/victron/scan/start`  | BLE-Scanning starten               |
| `POST /api/victron/scan/stop`   | BLE-Scanning stoppen               |
| `GET /api/victron/scan`         | Scan-Status                        |
| `GET /api/victron/dashboard`    | Alle Geräte in einem Call          |
| `GET /api/victron/mppt`         | Alle MPPT-Laderegler               |
| `GET /api/victron/mppt/{mac}`   | Einzelner MPPT                     |
| `GET /api/victron/shunt`        | Alle SmartShunts                   |
| `GET /api/victron/shunt/{mac}`  | Einzelner SmartShunt               |
| `GET /api/victron/orion`        | Alle Orion-Tr Converter            |
| `GET /api/victron/orion/{mac}`  | Einzelner Orion                    |
| `GET /q/metrics`                | Prometheus Metrics (Micrometer)    |

Rohe Messwerte werden zusätzlich lokal in eine **SQLite-DB** geschrieben
(`data/db/victron.db` bzw. `VICTRON_DB_PATH`). Tabellen: `mppt_reading`,
`shunt_reading`, `orion_reading` — eine Zeile pro BLE-Advertisement.

| `GET /q/health`                 | Health Check                       |

## Architektur

```
BLE Advertisement (BlueZ/D-Bus)
        │
        ▼
VictronBleScanner  ──►  AesCtrDecryptor  ──►  {Mppt,SmartShunt,Orion}Decoder
        │                                              │
        ▼                                              ▼
   (Scheduler, alle N s)                           DeviceStore (in-memory)
                                                       │
                                                       ▼
                                              VictronResource (REST)
```

## Hinweise zum Bit-Layout

Die Bit-Offsets in den Decodern sind aus der Open-Source-Library
[`victron-ble`](https://github.com/keshavdv/victron-ble) rekonstruiert.
Beim ersten Lauf empfiehlt sich ein Debug-Log der rohen entschlüsselten Bytes
und ein Abgleich mit den Werten in VictronConnect.

Bei älterer Orion-Firmware ohne Strommessung sind `inputCurrentA` /
`outputCurrentA` korrekterweise `null`.

## Lizenz

MIT
