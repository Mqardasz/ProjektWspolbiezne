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

import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Collectors;

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

    /** Comma-separated list of Zabbix host names to monitor concurrently. */
    @Value("${zabbix.host.names}")
    private List<String> zabbixHostNames;

    private final ConcurrentHashMap<String, AtomicReference<Metrics>> latestMetricsByHost =
            new ConcurrentHashMap<>();

    private final ConcurrentHashMap<String, Sinks.Many<Metrics>> sinksByHost =
            new ConcurrentHashMap<>();

    private WebClient webClient;

    @PostConstruct
    public void init() {
        webClient = WebClient.builder()
                .baseUrl(zabbixApiUrl)
                .build();

        // Initialise per-host state before polling starts.
        zabbixHostNames.forEach(host -> {
            latestMetricsByHost.put(host, new AtomicReference<>(emptyMetrics(host)));
            sinksByHost.put(host, Sinks.many().replay().latest());
        });

        startPolling();
    }

    // ─── Public API ───────────────────────────────────────────────────────────

    /** Returns the latest metrics for the first configured host (backward-compatible). */
    public Metrics getLatestMetrics() {
        if (zabbixHostNames.isEmpty()) return emptyMetrics("");
        return latestMetricsByHost
                .getOrDefault(zabbixHostNames.get(0), new AtomicReference<>(emptyMetrics("")))
                .get();
    }

    /** Returns the latest metrics for a specific host, or {@code null} if unknown. */
    public Metrics getLatestMetrics(String hostName) {
        AtomicReference<Metrics> ref = latestMetricsByHost.get(hostName);
        return ref != null ? ref.get() : null;
    }

    /** Returns the latest metrics for every monitored host. */
    public Map<String, Metrics> getAllLatestMetrics() {
        Map<String, Metrics> result = new LinkedHashMap<>();
        zabbixHostNames.forEach(host -> {
            AtomicReference<Metrics> ref = latestMetricsByHost.get(host);
            if (ref != null) result.put(host, ref.get());
        });
        return result;
    }

    /** Reactive stream for the first configured host (backward-compatible). */
    public Flux<Metrics> metricsStream() {
        if (zabbixHostNames.isEmpty()) return Flux.empty();
        return metricsStream(zabbixHostNames.get(0));
    }

    /** Reactive stream for a specific host. New subscribers immediately receive the latest value. */
    public Flux<Metrics> metricsStream(String hostName) {
        Sinks.Many<Metrics> sink = sinksByHost.get(hostName);
        return sink != null ? sink.asFlux() : Flux.empty();
    }

    /** Merged reactive stream for all monitored hosts. New subscribers receive the latest value per host. */
    public Flux<Metrics> allMetricsStream() {
        List<Flux<Metrics>> perHostFluxes = zabbixHostNames.stream()
                .map(host -> sinksByHost.getOrDefault(host,
                        Sinks.many().replay().latest()).asFlux())
                .collect(Collectors.toList());
        return Flux.merge(perHostFluxes);
    }

    // ─── Polling ─────────────────────────────────────────────────────────────

    private void startPolling() {
        Flux.interval(Duration.ofSeconds(POLL_INTERVAL_SECONDS))
                .startWith(0L) // emit immediately on startup
                .flatMap(tick ->
                        // Fetch all hosts concurrently; one host failing does not affect others.
                        Flux.fromIterable(zabbixHostNames)
                                .flatMap(host ->
                                        fetchMetricsForHost(host)
                                                .doOnError(ex -> log.warn(
                                                        "Zabbix unavailable for host '{}' ({}); keeping previous metrics",
                                                        host, ex.getMessage()))
                                                .onErrorResume(ex -> Mono.empty())
                                )
                )
                .subscribe(metrics -> {
                    String host = metrics.getHostName();
                    AtomicReference<Metrics> ref = latestMetricsByHost.get(host);
                    if (ref != null) ref.set(metrics);
                    Sinks.Many<Metrics> sink = sinksByHost.get(host);
                    if (sink != null) sink.tryEmitNext(metrics);
                    log.debug("Metrics updated for host '{}': cpu={} ram={} disk={} netIn={} netOut={}",
                            host, metrics.getCpu(), metrics.getRam(), metrics.getDisk(),
                            metrics.getNetworkIn(), metrics.getNetworkOut());
                });
    }

    // ─── Zabbix API calls ────────────────────────────────────────────────────

    private Mono<Metrics> fetchMetricsForHost(String hostName) {
        return authenticate()
                .flatMap(authToken -> getItems(authToken, hostName)
                        .map(items -> mapToMetrics(items, hostName)));
    }

    private Mono<String> authenticate() {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "user.login",
                "params", Map.of("username", zabbixUser, "password", zabbixPassword),
                "id", 1
        );

        return webClient.post()
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

    private Mono<JsonNode> getItems(String authToken, String hostName) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "item.get",
                "params", Map.of(
                        "output", List.of("key_", "lastvalue"),
                        "host", hostName,
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

    private Metrics mapToMetrics(JsonNode items, String hostName) {
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

        return new Metrics(cpu, ram, disk, netIn, netOut, Instant.now().getEpochSecond(), hostName);
    }

    // ─── Helpers ─────────────────────────────────────────────────────────────

    private double bytesToMbps(double bytesPerSecond) {
        return Math.round(bytesPerSecond * 8 / 1_000_000.0 * 100.0) / 100.0;
    }

    private static Metrics emptyMetrics(String hostName) {
        return new Metrics(0, 0, 0, 0, 0, Instant.now().getEpochSecond(), hostName);
    }
}