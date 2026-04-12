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

    private final AtomicLong seq = new AtomicLong(1000);

    @PostMapping(value = "/api_jsonrpc.php", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    public Map<String, Object> jsonrpc(@RequestBody Map<String, Object> req) {
        Object id = req.getOrDefault("id", 1);
        String method = String.valueOf(req.get("method"));

        return switch (method) {
            case "user.login" -> ok(id, "dummy-token-" + seq.incrementAndGet());
            case "item.get"   -> ok(id, items());
            default -> err(id, -32601, "Method not found: " + method);
        };
    }

    private List<Map<String, Object>> items() {
        long t = Instant.now().getEpochSecond();
        ThreadLocalRandom r = ThreadLocalRandom.current();

        // “Changing” values with small randomness + gentle drift
        double cpu  = clamp(5 + (t % 60) * 0.8 + r.nextDouble(-3, 3), 0, 100);
        double ram  = clamp(20 + (t % 120) * 0.4 + r.nextDouble(-2, 2), 0, 100);
        double disk = clamp(35 + (t % 300) * 0.05 + r.nextDouble(-0.5, 0.5), 0, 100);

        // bytes/sec (your service converts to Mbps)
        double netInBps  = clamp(200_000 + (t % 30) * 30_000 + r.nextDouble(-50_000, 50_000), 0, 50_000_000);
        double netOutBps = clamp(150_000 + (t % 45) * 25_000 + r.nextDouble(-50_000, 50_000), 0, 50_000_000);

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