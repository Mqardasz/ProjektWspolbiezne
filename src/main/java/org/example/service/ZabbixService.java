package org.example.service;

import com.fasterxml.jackson.databind.JsonNode;
import org.example.config.ZabbixProperties;
import org.example.model.Metrics;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Service;
import org.springframework.web.reactive.function.client.WebClient;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;

import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

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

    private final ZabbixProperties properties;

    /** Latest metrics snapshot per host name. */
    private final Map<String, Metrics> latestPerHost = new ConcurrentHashMap<>();

    /** Ordered list of host names (preserves config order). */
    private final List<String> hostOrder = new ArrayList<>();

    /** Per-host WebClient instances (keyed by host name). */
    private final Map<String, WebClient> webClients = new LinkedHashMap<>();

    private final Sinks.Many<List<Metrics>> metricsSink =
            Sinks.many().replay().latest();

    public ZabbixService(ZabbixProperties properties) {
        this.properties = properties;
    }

    @PostConstruct
    public void init() {
        List<ZabbixProperties.HostConfig> hosts = properties.getHosts();
        if (hosts.isEmpty()) {
            log.warn("No Zabbix hosts configured under 'zabbix.hosts'");
            return;
        }

        for (ZabbixProperties.HostConfig host : hosts) {
            hostOrder.add(host.getName());
            latestPerHost.put(host.getName(), emptyMetrics(host.getName()));
            webClients.put(host.getName(), WebClient.builder()
                    .baseUrl(host.getUrl())
                    .build());
        }

        startPolling();
    }

    /** Returns the latest metrics for the first configured host (backward compatibility). */
    public Metrics getLatestMetrics() {
        if (hostOrder.isEmpty()) return emptyMetrics("unknown");
        return latestPerHost.get(hostOrder.get(0));
    }

    /** Returns the latest metrics snapshot for every configured host. */
    public List<Metrics> getAllLatestMetrics() {
        List<Metrics> result = new ArrayList<>(hostOrder.size());
        for (String name : hostOrder) {
            result.add(latestPerHost.get(name));
        }
        return result;
    }

    /** Live stream – each emission contains the full snapshot for all hosts. */
    public Flux<List<Metrics>> metricsStream() {
        return metricsSink.asFlux();
    }

    // ──────────────────────────────────────────────────────────────────────────

    private void startPolling() {
        Flux.interval(Duration.ofSeconds(POLL_INTERVAL_SECONDS))
                .startWith(0L)
                .flatMap(tick -> fetchAllMetrics())
                .subscribe(metricsList -> {
                    for (Metrics m : metricsList) {
                        latestPerHost.put(m.getHostName(), m);
                        log.debug("Metrics updated [{}]: cpu={} ram={} disk={} netIn={} netOut={}",
                                m.getHostName(), m.getCpu(), m.getRam(), m.getDisk(),
                                m.getNetworkIn(), m.getNetworkOut());
                    }
                    metricsSink.tryEmitNext(new ArrayList<>(metricsList));
                });
    }

    /**
     * Fetches metrics from all configured hosts concurrently.
     * Hosts that fail keep their previous snapshot.
     */
    private Mono<List<Metrics>> fetchAllMetrics() {
        List<ZabbixProperties.HostConfig> hosts = properties.getHosts();
        return Flux.fromIterable(hosts)
                .flatMap(host -> fetchMetricsForHost(host)
                        .doOnError(ex -> log.warn(
                                "Zabbix unavailable for host '{}' ({}); keeping previous metrics",
                                host.getName(), ex.getMessage()))
                        .onErrorResume(ex -> Mono.just(latestPerHost.get(host.getName())))
                )
                .collectList();
    }

    private Mono<Metrics> fetchMetricsForHost(ZabbixProperties.HostConfig host) {
        WebClient client = webClients.get(host.getName());
        return authenticate(client, host)
                .flatMap(token -> getItems(client, host, token))
                .map(items -> mapToMetrics(host.getName(), items));
    }

    private Mono<String> authenticate(WebClient client, ZabbixProperties.HostConfig host) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "user.login",
                "params", Map.of("username", host.getUser(), "password", host.getPassword()),
                "id", 1
        );

        return client.post()
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

    private Mono<JsonNode> getItems(WebClient client, ZabbixProperties.HostConfig host, String authToken) {
        Map<String, Object> body = Map.of(
                "jsonrpc", "2.0",
                "method", "item.get",
                "params", Map.of(
                        "output", List.of("key_", "lastvalue"),
                        "host", host.getName(),
                        "filter", Map.of("key_", List.of(KEY_CPU, KEY_RAM, KEY_DISK, KEY_NET_IN, KEY_NET_OUT))
                ),
                "auth", authToken,
                "id", 2
        );

        return client.post()
                .contentType(MediaType.APPLICATION_JSON)
                .accept(MediaType.APPLICATION_JSON)
                .bodyValue(body)
                .retrieve()
                .bodyToMono(JsonNode.class)
                .map(json -> json.path("result"));
    }

    private Metrics mapToMetrics(String hostName, JsonNode items) {
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

        return new Metrics(hostName, cpu, ram, disk, netIn, netOut, Instant.now().getEpochSecond());
    }

    private double bytesToMbps(double bytesPerSecond) {
        return Math.round(bytesPerSecond * 8 / 1_000_000.0 * 100.0) / 100.0;
    }

    private static Metrics emptyMetrics(String hostName) {
        return new Metrics(hostName, 0, 0, 0, 0, 0, Instant.now().getEpochSecond());
    }
}