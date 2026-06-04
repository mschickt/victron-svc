package de.mhome.victron.boundary;

import de.mhome.victron.config.DeviceRegistry;
import de.mhome.victron.entity.MpptData;
import de.mhome.victron.entity.OrionData;
import de.mhome.victron.entity.SmartShuntData;
import de.mhome.victron.control.DeviceStore;
import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;

@Path("/api/victron")
@Produces(MediaType.APPLICATION_JSON)
public class VictronResource {

    @Inject DeviceStore store;
    @Inject VictronBleScanner scanner;
    @Inject DeviceRegistry deviceRegistry;

    // ── Konfigurierte Geräte + Last-Seen ─────────────────────────────────

    @GET
    @Path("/devices")
    public List<DeviceStatus> devices() {
        Instant now = Instant.now();
        return deviceRegistry.devices().stream()
            .map(d -> {
                Instant seen = store.getLastSeen(d.mac()).orElse(null);
                return new DeviceStatus(
                    d.mac(),
                    d.name(),
                    d.type().name(),
                    seen == null ? null : seen.toString(),
                    seen == null ? null : Duration.between(seen, now).toSeconds()
                );
            })
            .toList();
    }

    public record DeviceStatus(
        String mac,
        String name,
        String type,
        String lastSeen,
        Long   secondsSinceLastSeen
    ) {}

    // ── BLE Scan-Steuerung (standardmäßig aus, per API aktivieren) ───────

    @POST
    @Path("/scan/start")
    public ScanStatus startScan() {
        boolean discoveryActive = scanner.enableScanning();
        return new ScanStatus(scanner.isScanningEnabled(), discoveryActive);
    }

    @POST
    @Path("/scan/stop")
    public ScanStatus stopScan() {
        scanner.disableScanning();
        return new ScanStatus(scanner.isScanningEnabled(), false);
    }

    @GET
    @Path("/scan")
    public ScanStatus scanStatus() {
        return new ScanStatus(scanner.isScanningEnabled(), scanner.isDiscovering());
    }

    public record ScanStatus(boolean scanning, boolean discoveryActive) {}

    // ── Alle Geräte ─────────────────────────────────────────────────────

    @GET
    @Path("/mppt")
    public Map<String, MpptData> allMppt() {
        return store.getAllMppt();
    }

    @GET
    @Path("/shunt")
    public Map<String, SmartShuntData> allShunt() {
        return store.getAllShunt();
    }

    @GET
    @Path("/orion")
    public Map<String, OrionData> allOrion() {
        return store.getAllOrion();
    }

    // ── Einzelne Geräte nach MAC ─────────────────────────────────────────

    @GET
    @Path("/mppt/{mac}")
    public Response mpptByMac(@PathParam("mac") String mac) {
        return store.getMppt(mac)
            .map(d -> Response.ok(d).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "MPPT nicht gefunden: " + mac)).build());
    }

    @GET
    @Path("/shunt/{mac}")
    public Response shuntByMac(@PathParam("mac") String mac) {
        return store.getShunt(mac)
            .map(d -> Response.ok(d).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "SmartShunt nicht gefunden: " + mac)).build());
    }

    @GET
    @Path("/orion/{mac}")
    public Response orionByMac(@PathParam("mac") String mac) {
        return store.getOrion(mac)
            .map(d -> Response.ok(d).build())
            .orElse(Response.status(Response.Status.NOT_FOUND)
                .entity(Map.of("error", "Orion nicht gefunden: " + mac)).build());
    }

    // ── Dashboard: alle Geräte in einem Call ─────────────────────────────

    @GET
    @Path("/dashboard")
    public DashboardResponse dashboard() {
        return new DashboardResponse(
            store.getAllMppt(),
            store.getAllShunt(),
            store.getAllOrion()
        );
    }

    public record DashboardResponse(
        Map<String, MpptData>       mppt,
        Map<String, SmartShuntData> shunt,
        Map<String, OrionData>      orion
    ) {}

    // Prometheus metrics are exposed by Micrometer at /q/metrics
}
