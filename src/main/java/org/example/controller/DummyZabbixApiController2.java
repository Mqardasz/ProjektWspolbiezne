package org.example.controller;

import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.*;

import java.time.Instant;
import java.util.*;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicLong;

@RestController
@RequestMapping("/dummy-zabbix-2")
public class DummyZabbixApiController2 {

    private static final String KEY_CPU     = "system.cpu.util";
    private static final String KEY_RAM     = "vm.memory.utilization";
    private static final String KEY_DISK    = "vfs.fs.dependent.size[/,pused]";
    private static final String KEY_NET_IN  = "net.if.in[\"eth0\"]";
    private static final String KEY_NET_OUT = "net.if.out[\"eth0\"]";

    private final AtomicLong seq = new AtomicLong(2000);

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

        // Higher baseline CPU, faster RAM cycle, different network throughput
        double cpu  = clamp(40 + (t % 90) * 0.6 + r.nextDouble(-5, 5), 0, 100);
        double ram  = clamp(55 + (t % 60) * 0.3 + r.nextDouble(-3, 3), 0, 100);
        double disk = clamp(60 + (t % 200) * 0.04 + r.nextDouble(-0.5, 0.5), 0, 100);

        double netInBps  = clamp(500_000 + (t % 20) * 50_000 + r.nextDouble(-80_000, 80_000), 0, 50_000_000);
        double netOutBps = clamp(300_000 + (t % 35) * 40_000 + r.nextDouble(-60_000, 60_000), 0, 50_000_000);

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
