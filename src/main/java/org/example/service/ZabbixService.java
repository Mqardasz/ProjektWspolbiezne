package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.model.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;

import jakarta.annotation.PostConstruct;

/**
 * Reactive service that polls Zabbix API every 5 seconds using Project Reactor.
 *
 * <p>Authentication flow:
 * <ol>
 *   <li>POST user.login  → obtain authToken</li>
 *   <li>POST item.get    → retrieve latest metric values</li>
 * </ol>
 *
 * <p>If the Zabbix server is unreachable the service falls back to simulated
 * data so the dashboard always has something meaningful to display.
 */
@Service
public class ZabbixService {

    private static final Logger log = LoggerFactory.getLogger(ZabbixService.class);

    private static final int POLL_INTERVAL_SECONDS = 5;

    /** Zabbix item keys used to identify the desired metrics. */
    private static final String KEY_CPU     = "system.cpu.util";
    private static final String KEY_RAM     = "vm.memory.utilization";
    private static final String KEY_DISK    = "vfs.fs.size[/,pused]";
    private static final String KEY_NET_IN  = "net.if.in[eth0]";
    private static final String KEY_NET_OUT = "net.if.out[eth0]";

    @Value("${zabbix.api.url:http://localhost/zabbix/api_jsonrpc.php}")
    private String zabbixApiUrl;

    @Value("${zabbix.api.user:Admin}")
    private String zabbixUser;

    @Value("${zabbix.api.password:zabbix}")
    private String zabbixPassword;

    @Value("${zabbix.host.name:Linux server}")
    private String zabbixHostName;

    private final AtomicReference<Metrics> latestMetrics = new AtomicReference<>(emptyMetrics());

    private WebClient webClient;

    @PostConstruct
    public void init() {
        webClient = WebClient.builder()
                .baseUrl(zabbixApiUrl)
                .defaultHeader("Content-Type", "application/json")
                .build();

        startPolling();
    }

    /**
     * Returns the most recently collected {@link Metrics} snapshot.
     */
    public Metrics getLatestMetrics() {
        return latestMetrics.get();
    }

    // -------------------------------------------------------------------------
    // Reactor polling pipeline
    // -------------------------------------------------------------------------

    private void startPolling() {
        Flux.interval(Duration.ofSeconds(POLL_INTERVAL_SECONDS))
                .startWith(0L)                         // emit immediately on startup
                .flatMap(tick -> fetchMetrics()
                        .onErrorResume(ex -> {
                            log.warn("Zabbix unavailable ({}); using simulated data", ex.getMessage());
                            return Mono.just(simulatedMetrics());
                        }))
                .subscribe(metrics -> {
                    latestMetrics.set(metrics);
                    log.debug("Metrics updated: cpu={} ram={} disk={} netIn={} netOut={}",
                            metrics.getCpu(), metrics.getRam(), metrics.getDisk(),
                            metrics.getNetworkIn(), metrics.getNetworkOut());
                });
    }

    // -------------------------------------------------------------------------
    // Zabbix API interaction
    // -------------------------------------------------------------------------

    /**
     * Authenticates against Zabbix, then retrieves item values for the
     * configured host.  The entire chain is non-blocking.
     */
    private Mono<Metrics> fetchMetrics() {
        return authenticate()
                .flatMap(authToken -> getItems(authToken)
                        .map(items -> mapToMetrics(items)));
    }

    /**
     * Calls {@code user.login} and returns the authentication token.
     */
    private Mono<String> authenticate() {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "user.login",
                "params", Map.of("username", zabbixUser, "password", zabbixPassword),
                "id", 1
        );

        return webClient.post()
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json.path("result").asText());
    }

    /**
     * Calls {@code item.get} to retrieve the latest values for the five
     * monitored keys on the configured host.
     */
    private Mono<JsonNode> getItems(String authToken) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "item.get",
                "params", Map.of(
                        "output", List.of("key_", "lastvalue"),
                        "host", zabbixHostName,
                        "search", Map.of("key_", ""),
                        "filter", Map.of("key_", List.of(KEY_CPU, KEY_RAM, KEY_DISK, KEY_NET_IN, KEY_NET_OUT))
                ),
                "auth", authToken,
                "id", 2
        );

        return webClient.post()
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json.path("result"));
    }

    /**
     * Maps the JSON array returned by {@code item.get} to a {@link Metrics} object.
     */
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
                default -> { /* ignored */ }
            }
        }

        return new Metrics(cpu, ram, disk, netIn, netOut, Instant.now().getEpochSecond());
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    /** Convert bytes/s to Mbps (rounded). */
    private double bytesToMbps(double bytesPerSecond) {
        return Math.round(bytesPerSecond * 8 / 1_000_000.0 * 100.0) / 100.0;
    }

    /** Simulated metric values used when the Zabbix server is unreachable. */
    private Metrics simulatedMetrics() {
        double cpu     = 20 + Math.random() * 60;
        double ram     = 30 + Math.random() * 50;
        double disk    = 40 + Math.random() * 40;
        double netIn   = Math.round(Math.random() * 200 * 100) / 100.0;
        double netOut  = Math.round(Math.random() * 150 * 100) / 100.0;
        return new Metrics(
                Math.round(cpu  * 10) / 10.0,
                Math.round(ram  * 10) / 10.0,
                Math.round(disk * 10) / 10.0,
                netIn, netOut,
                Instant.now().getEpochSecond()
        );
    }

    private static Metrics emptyMetrics() {
        return new Metrics(0, 0, 0, 0, 0, Instant.now().getEpochSecond());
    }
}
