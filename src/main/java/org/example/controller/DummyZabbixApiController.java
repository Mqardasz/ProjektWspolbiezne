package org.example.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/dummy-zabbix")
public class DummyZabbixApiController {

    private static final String KEY_CPU     = "system.cpu.util";
    private static final String KEY_RAM     = "vm.memory.utilization";
    private static final String KEY_DISK    = "vfs.fs.dependent.size[/,pused]";
    private static final String KEY_NET_IN  = "net.if.in[\"eth0\"]";
    private static final String KEY_NET_OUT = "net.if.out[\"eth0\"]";

    /**
     * Per-host base offsets: {cpuBase, ramBase, diskBase, netInBase, netOutBase}.
     * Each host has a distinct "personality" so metrics never overlap.
     */
    private static final Map<String, double[]> HOST_PROFILES = Map.of(
            "Zabbix server", new double[]{  5, 20, 35,  200_000, 150_000 },
            "Host Alpha",    new double[]{ 25, 45, 55,  500_000, 300_000 },
            "Host Beta",     new double[]{ 50, 70, 75, 1_000_000, 800_000 }
    );

    private final AtomicLong seq = new AtomicLong(1000);

    @PostMapping(value = "/api_jsonrpc.php",
            consumes = MediaType.APPLICATION_JSON_VALUE,
            produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jsonrpc(@RequestBody Map<String, Object> req) {
        Object id = req.getOrDefault("id", 1);
        String method = String.valueOf(req.get("method"));

        return switch (method) {
            case "user.login" -> ok(id, "dummy-token-" + seq.incrementAndGet());
            case "item.get"   -> {
                @SuppressWarnings("unchecked")
                Map<String, Object> params = (Map<String, Object>) req.get("params");
                String host = params != null
                        ? String.valueOf(params.getOrDefault("host", "Zabbix server"))
                        : "Zabbix server";
                yield ok(id, items(host));
            }
            default -> err(id, -32601, "Method not found: " + method);
        };
    }

    private List<Map<String, Object>> items(String host) {
        long t = Instant.now().getEpochSecond();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        // Fall back to "Zabbix server" profile for unknown hosts.
        double[] p = HOST_PROFILES.getOrDefault(host, HOST_PROFILES.get("Zabbix server"));
        double cpuBase    = p[0];
        double ramBase    = p[1];
        double diskBase   = p[2];
        double netInBase  = p[3];
        double netOutBase = p[4];

        double cpu  = clamp(cpuBase  + (t % 60)  * 0.8  + r.nextDouble(-3, 3),       0, 100);
        double ram  = clamp(ramBase  + (t % 120) * 0.4  + r.nextDouble(-2, 2),       0, 100);
        double disk = clamp(diskBase + (t % 300) * 0.05 + r.nextDouble(-0.5, 0.5),   0, 100);

        double netInBps  = clamp(netInBase  + (t % 30) * 30_000 + r.nextDouble(-50_000, 50_000), 0, 50_000_000);
        double netOutBps = clamp(netOutBase + (t % 45) * 25_000 + r.nextDouble(-50_000, 50_000), 0, 50_000_000);

        return List.of(
                item(KEY_CPU, cpu),
                item(KEY_RAM, ram),
                item(KEY_DISK, disk),
                item(KEY_NET_IN, netInBps),
                item(KEY_NET_OUT, netOutBps)
        );
    }

    private Map<String, Object> item(String key, double lastvalue) {
        return Map.of(
                "key_", key,
                "lastvalue", String.format(Locale.US, "%.4f", lastvalue)
        );
    }

    private Map<String, Object> ok(Object id, Object result) {
        return Map.of(
                "jsonrpc", "2.0",
                "result", result,
                "id", id
        );
    }

    private Map<String, Object> err(Object id, int code, String message) {
        return Map.of(
                "jsonrpc", "2.0",
                "error", Map.of("code", code, "message", message),
                "id", id
        );
    }

    private static double clamp(double v, double min, double max) {
        return Math.max(min, Math.min(max, v));
    }
}
