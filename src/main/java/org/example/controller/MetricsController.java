package org.example.controller;

import org.example.model.Metrics;
import org.example.service.ZabbixService;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.util.List;

// GET /metrics - wszystkie hosty (pierwszy get po załadowaniu)
// GET /metrics/stream – strumień hostów
@RestController
public class MetricsController {

    private final ZabbixService zabbixService;

    public MetricsController(ZabbixService zabbixService) {
        this.zabbixService = zabbixService;
    }

    @GetMapping(value = "/metrics", produces = MediaType.APPLICATION_JSON_VALUE)
    public Mono<List<Metrics>> getMetrics() {
        return Mono.just(zabbixService.getAllLatestMetrics());
    }

    @GetMapping(value = "/metrics/stream", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    public Flux<List<Metrics>> streamMetrics() {
        return zabbixService.metricsStream();
    }
}
