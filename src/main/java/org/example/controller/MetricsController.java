package org.example.controller;

import org.example.model.Metrics;
import org.example.service.ZabbixService;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.Map;

/**
 * Exposes the latest server metrics collected by {@link ZabbixService}.
 *
 * <pre>
 * GET /metrics                        – latest metrics for the first configured host (backward-compat)
 * GET /metrics/stream                 – SSE stream for the first configured host (backward-compat)
 * GET /metrics/hosts                  – latest metrics for all monitored hosts
 * GET /metrics/hosts/{hostName}       – latest metrics for a specific host
 * GET /metrics/hosts/stream           – SSE stream for all hosts (interleaved)
 * GET /metrics/hosts/{hostName}/stream – SSE stream for a specific host
 * </pre>
 */
@RestController
public class MetricsController {

    private final ZabbixService zabbixService;

    public MetricsController(ZabbixService zabbixService) {
        this.zabbixService = zabbixService;
    }

    // ─── Backward-compatible single-host endpoints ────────────────────────────

    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Metrics> getMetrics() {
        return Mono.just(zabbixService.getLatestMetrics());
    }

    @GetMapping(value = "/metrics/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Metrics> streamMetrics() {
        return zabbixService.metricsStream();
    }

    // ─── Multi-host endpoints ─────────────────────────────────────────────────

    @GetMapping(value = "/metrics/hosts", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Map<String, Metrics>> getAllMetrics() {
        return Mono.just(zabbixService.getAllLatestMetrics());
    }

    @GetMapping(value = "/metrics/hosts/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Metrics> streamAllMetrics() {
        return zabbixService.allMetricsStream();
    }

    @GetMapping(value = "/metrics/hosts/{hostName}", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<ResponseEntity<Metrics>> getMetricsForHost(@PathVariable String hostName) {
        Metrics m = zabbixService.getLatestMetrics(hostName);
        return Mono.just(m != null ? ResponseEntity.ok(m) : ResponseEntity.notFound().build());
    }

    @GetMapping(value = "/metrics/hosts/{hostName}/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<Metrics> streamMetricsForHost(@PathVariable String hostName) {
        return zabbixService.metricsStream(hostName);
    }
}
