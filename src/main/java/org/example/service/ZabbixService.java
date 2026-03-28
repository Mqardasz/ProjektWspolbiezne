package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.model.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import reactor.core.publisher.Sinks;
import reactor.netty.http.client.HttpClient;
import reactor.netty.transport.ProxyProvider;
import org.springframework.http.client.reactive.ReactorClientHttpConnector;
import org.springframework.web.reactive.function.client.WebClient;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

@Service
public class ZabbixService {

    private static final Logger log = LoggerFactory.getLogger(ZabbixService.class);

    private static final int POLL_INTERVAL_SECONDS = 5;

    private static final String KEY_CPU     = "system.cpu.util";
    private static final String KEY_RAM     = "vm.memory.utilization";
    private static final String KEY_DISK    = "vfs.fs.dependent.size[/,pused]";
    private static final String KEY_NET_IN  = "net.if.in[\"eth0\"]";
    private static final String KEY_NET_OUT = "net.if.out[\"eth0\"]";

    @Value("${zabbix.api.url}")
    private String zabbixApiUrl;

    @Value("${zabbix.api.user}")
    private String zabbixUser;

    @Value("${zabbix.api.password}")
    private String zabbixPassword;

    @Value("${zabbix.host.name}")
    private String zabbixHostName;

    private final AtomicReference<Metrics> latestMetrics = new AtomicReference<>(emptyMetrics());

    private WebClient webClient;

    @PostConstruct
    public void init() {
        webClient = WebClient.builder()
                // zabbixApiUrl already includes /zabbix/api_jsonrpc.php
                .baseUrl(zabbixApiUrl)
                .build();

        startPolling();
    }

    public Metrics getLatestMetrics() {
        return latestMetrics.get();
    }

    private void startPolling() {
        Flux.interval(Duration.ofSeconds(POLL_INTERVAL_SECONDS))
                .startWith(0L) // emit immediately on startup
                .flatMap(tick -> fetchMetrics()
                        .doOnError(ex ->
                                log.warn("Zabbix unavailable ({}); keeping previous metrics", ex.getMessage()))
                        .onErrorResume(ex -> Mono.empty()) // skip update on failure
                )
                .subscribe(metrics -> {
                    latestMetrics.set(metrics);
                    metricsSink.tryEmitNext(metrics);
                    log.debug("Metrics updated: cpu={} ram={} disk={} netIn={} netOut={}",
                            metrics.getCpu(), metrics.getRam(), metrics.getDisk(),
                            metrics.getNetworkIn(), metrics.getNetworkOut());
                });
    }

    private Mono<Metrics> fetchMetrics() {
        return authenticate()
                .flatMap(authToken -> getItems(authToken).map(this::mapToMetrics));
    }

    private Mono<String> authenticate() {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "user.login",
                "params", Map.of("username", zabbixUser, "password", zabbixPassword),
                "id", 1
        );

        return webClient.post()
                // POST directly to baseUrl (api_jsonrpc.php). No .uri(zabbixHostName)!
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> {
                    JsonNode result = json.get("result");
                    return result != null ? result.asText() : "";
                });
    }

    private Mono<JsonNode> getItems(String authToken) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "item.get",
                "params", Map.of(
                        "output", List.of("key_", "lastvalue"),
                        // the Zabbix "host" field is the host name in Zabbix ("Zabbix server")
                        "host", zabbixHostName,
                        "filter", Map.of("key_", List.of(KEY_CPU, KEY_RAM, KEY_DISK, KEY_NET_IN, KEY_NET_OUT))
                ),
                "auth", authToken,
                "id", 2
        );

        return webClient.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json.path("result"));
    }

    private Metrics mapToMetrics(JsonNode items) {
        double cpu = 0, ram = 0, disk = 0, netIn = 0, netOut = 0;

        for (JsonNode item : items) {
            String key = item.path("key_").asText();
            double value = item.path("lastvalue").asDouble();

            switch (key) {
                case KEY_CPU     -> cpu    = value;
                case KEY_RAM     -> ram    = value;
                case KEY_DISK    -> disk   = value;
                case KEY_NET_IN  -> netIn  = bytesToMbps(value);
                case KEY_NET_OUT -> netOut = bytesToMbps(value);
                default -> log.debug("Ignoring unrecognised Zabbix item key: {}", key);
            }
        }

        return new Metrics(cpu, ram, disk, netIn, netOut, Instant.now().getEpochSecond());
    }

    private double bytesToMbps(double bytesPerSecond) {
        return Math.round(bytesPerSecond * 8 / 1_000_000.0 * 100.0) / 100.0;
    }

    private static Metrics emptyMetrics() {
        return new Metrics(0, 0, 0, 0, 0, Instant.now().getEpochSecond());
    }

    private final Sinks.Many<Metrics> metricsSink =
            Sinks.many().replay().latest(); // new subscribers get latest immediately

    public Flux<Metrics> metricsStream() {
        return metricsSink.asFlux();
    }
}