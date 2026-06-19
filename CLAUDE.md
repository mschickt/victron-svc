# CLAUDE.md

This file provides guidance to Claude Code (claude.ai/code) when working with code in this repository.

## Project

Quarkus 3.36.x service (Azul Zulu JDK 25) that reads Victron devices (MPPT, SmartShunt, Orion-Tr Smart) via BLE "Instant Readout" advertisements and exposes the values over REST + Prometheus. Requires Linux + BlueZ at runtime (the `bluez-dbus` library talks to BlueZ over D-Bus), so the BLE scanner cannot run on macOS — only build/tests/decoders work locally.

The Maven Wrapper (`./mvnw`) in the tree is broken (`.mvn/wrapper/` is missing) — use system `mvn` instead.

## Commands

```bash
mvn quarkus:dev                              # dev mode, hot reload
mvn test                                     # all tests
mvn test -Dtest=SmartShuntDecoderTest        # single test class
mvn test -Dtest=BitReaderTest#methodName     # single test method
mvn package                                  # builds target/quarkus-app/quarkus-run.jar
java -jar target/quarkus-app/quarkus-run.jar # run production build

docker compose up -d --build                 # full stack: svc + Prometheus + Grafana
```

Device MACs and advertisement keys live in `src/main/resources/application.yml`. Real keys should go in `application-local.yml` (gitignored) or env vars like `VICTRON_DEVICES_0_MAC` / `VICTRON_DEVICES_0_ADVERTISEMENT_KEY`. Get the advertisement key in VictronConnect: Device → ⋮ → Produktinfo → Advertisement Key.

## Architecture

Package layout follows the **Boundary-Control-Entity** pattern (Adam Bien). Data flow is one-way, BLE advertisement → REST/Prometheus:

```
BlueZ/D-Bus  →  VictronBleScanner  →  AesCtrDecryptor  →  {Mppt,SmartShunt,Orion}Decoder  →  DeviceStore  →  VictronResource (REST)
                                                                                                       └→  Micrometer gauges → /q/metrics
```

- `boundary/VictronBleScanner` — scheduled poller, reads BLE manufacturer data via bluez-dbus, dispatches by MAC to the configured device + decoder. Quarkus startup event sets up the BlueZ adapter + discovery filter. **bluez-dbus API note:** manufacturer IDs are `UInt16` and discovery filter values are `Variant<?>` — don't mistype these as `short` or raw `Object`.
- `boundary/DalyBleScanner` — **connection-based** poller for Bulltron batteries (`Vendor.BULLTRON` / `DeviceType.BATTERY`), a rebadged Daly Smart BMS. Daly does NOT broadcast in advertisements, so this is a separate path from `VictronBleScanner`: it shares the same `DeviceManager`, opens a GATT connection, subscribes to the notify characteristic, writes Daly request frames, and reads notifications. Service `0xff00`, notify `0xff01` (RX), write `0xff02` (TX). Notifications arrive as BlueZ `PropertiesChanged` on `Value` — routed via `registerPropertyHandler` keyed by characteristic D-Bus path. Poll interval `victron.ble.battery-poll-interval` (default 30s). Only one BLE connection per device: the Bulltron phone app must be disconnected.
- `control/DalyBmsDecoder` — pure Daly protocol codec: builds 13-byte request frames (`A5 80 <cmd> 08 <8 data> <crc=sum&0xFF>`), validates/extracts response payloads, decodes commands 0x90/0x91/0x92/0x93/0x94. Offsets verified against [`python-daly-bms`](https://github.com/dreadnought/python-daly-bms) and [`batmon-ha`](https://github.com/fl4p/batmon-ha). Current sign is normalized to the Victron convention (negative = discharge; Daly is the opposite).
- `boundary/VictronResource` — JAX-RS endpoints under `/api/victron/*` (`dashboard`, `mppt`, `shunt`, `orion`, `battery`). `ble/devices` lists all nearby BLE devices for setup purposes (finding MACs/keys) and is filtered through `config/BleBlacklist` (`victron.ble.blacklist-names`/`blacklist-macs`, comma-separated, name match is a case-insensitive prefix) to hide irrelevant nearby devices (phones, other beacons). Prometheus metrics live at `/q/metrics` (Micrometer), health at `/q/health`.
- `control/AesCtrDecryptor` — Victron uses AES-CTR with the per-device advertisement key; nonce/IV derives from the advertisement header (2-byte LE nonce + 14 zero bytes).
- `control/BitReader` + `control/RecordType` — Victron payloads are bit-packed LSB-first (not byte-aligned); decoders walk fields via `BitReader`. RecordType selects the layout.
- `control/{Mppt,SmartShunt,Orion}Decoder` — bit offsets reconstructed from the [`victron-ble`](https://github.com/keshavdv/victron-ble) Python library. When adjusting layouts, cross-check against that repo and log raw decrypted bytes against VictronConnect values.
- `control/DeviceStore` — in-memory latest-value cache keyed by MAC, plus registers a set of Micrometer gauges per device on first update (gauges read from the map by reference, so subsequent updates are picked up automatically). Long-term aggregates live in Prometheus' TSDB.
- `control/ReadingRepository` — appends every decoded advertisement/poll to a local SQLite DB (`data/db/victron.db`, override via `VICTRON_DB_PATH`). Tables `mppt_reading`, `shunt_reading`, `orion_reading`, `battery_reading` are created on startup; one row per BLE frame/poll cycle.
- `entity/{MpptData,SmartShuntData,OrionData,BulltronData}` — immutable Jackson records. Older Orion firmware without current sensing legitimately returns `null` for `inputCurrentA` / `outputCurrentA`; that nullability is part of the contract. `BulltronData` fields are `null` when the corresponding Daly command wasn't answered in a poll cycle.
- `config/VictronConfig` — `@ConfigMapping` for the device list.

## Deployment

- `Dockerfile` — multi-stage build using `azul/zulu-openjdk:25`. Runtime image is JRE 25 + `dbus`.
- `docker-compose.yml` — runs victron-svc + Prometheus + Grafana, all `network_mode: host`, so they reach each other via `localhost`. `/var/run/dbus` is mounted into victron-svc with `NET_ADMIN`/`NET_RAW` caps so BlueZ on `hci0` is reachable.
- `deploy/prometheus.yml` — scrape config (`localhost:8090/q/metrics`, 15s).
- `deploy/grafana/provisioning/` — datasource + dashboard provider configs.
- `deploy/grafana/dashboards/victron.json` — starter dashboard (battery V/A, SoC, solar power, Orion output).
- `deploy/victron-svc.service` — systemd unit for native Raspbian install, runs as user `victron`, group `bluetooth`, with `CAP_NET_ADMIN`/`CAP_NET_RAW` ambient caps. Env file at `/etc/victron-svc.env`.
