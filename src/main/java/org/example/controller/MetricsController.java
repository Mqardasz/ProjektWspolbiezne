package org.example.controller;

import org.example.model.Metrics;
import org.example.service.ZabbixService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Mono;

/**
 * Exposes the latest server metrics collected by {@link ZabbixService}.
 *
 * <pre>GET /metrics</pre>
 */
@RestController
public class MetricsController {

    private final ZabbixService zabbixService;

    public MetricsController(ZabbixService zabbixService) {
        this.zabbixService = zabbixService;
    }

    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<Metrics> getMetrics() {
        return Mono.just(zabbixService.getLatestMetrics());
    }
}
