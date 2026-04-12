package org.example;

import org.example.model.Metrics;
import org.example.service.ZabbixService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;
import reactor.test.StepVerifier;

import java.time.Duration;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses DEFINED_PORT so the embedded server starts on the port configured in
 * application.properties (8080), which is also where the dummy Zabbix API
 * controller is reachable.  This allows ZabbixService to talk to the
 * in-process dummy controller during tests.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
class MetricsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ZabbixService zabbixService;

    /** Verifies the JSON structure returned by GET /metrics. */
    @Test
    void metricsEndpointReturnsJson() {
        webTestClient.get()
                .uri("/metrics")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cpu").exists()
                .jsonPath("$.ram").exists()
                .jsonPath("$.disk").exists()
                .jsonPath("$.networkIn").exists()
                .jsonPath("$.networkOut").exists()
                .jsonPath("$.timestamp").exists();
    }

    /** Verifies that the service returns a non-null snapshot with a valid timestamp. */
    @Test
    void zabbixServiceReturnsNonNullMetrics() {
        Metrics m = zabbixService.getLatestMetrics();
        assertThat(m).isNotNull();
        assertThat(m.getTimestamp()).isGreaterThan(0L);
    }

    /**
     * Verifies that the service populates metrics even when Zabbix is
     * unreachable (simulated-data fallback). We wait up to 10 seconds for the
     * first non-zero value to appear (the polling Flux starts immediately on
     * application startup, so it should already be set by now).
     */
    @Test
    void metricsArePopulatedWhenZabbixIsUnavailable() {
        StepVerifier.create(
                        reactor.core.publisher.Mono
                                .fromCallable(zabbixService::getLatestMetrics)
                                .repeatWhen(flux -> flux.delayElements(Duration.ofMillis(500)))
                                .filter(m -> m.getCpu() > 0 || m.getRam() > 0)
                                .next())
                .expectNextMatches(m -> {
                    assertThat(m.getCpu()).isBetween(0.0, 100.0);
                    assertThat(m.getRam()).isBetween(0.0, 100.0);
                    assertThat(m.getDisk()).isBetween(0.0, 100.0);
                    assertThat(m.getNetworkIn()).isGreaterThanOrEqualTo(0.0);
                    assertThat(m.getNetworkOut()).isGreaterThanOrEqualTo(0.0);
                    return true;
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    /** Verifies that GET /metrics/hosts returns metrics for all three configured hosts. */
    @Test
    void allHostsEndpointReturnsMetricsForEveryHost() {
        webTestClient.get()
                .uri("/metrics/hosts")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$['Zabbix server']").exists()
                .jsonPath("$['Host Alpha']").exists()
                .jsonPath("$['Host Beta']").exists()
                .jsonPath("$['Zabbix server'].cpu").exists()
                .jsonPath("$['Host Alpha'].cpu").exists()
                .jsonPath("$['Host Beta'].cpu").exists();
    }

    /** Verifies per-host separation: each host has its own latest metrics map entry. */
    @Test
    void perHostMetricsSeparationIsCorrect() {
        // Wait up to 10 s for all hosts to have non-zero metrics.
        StepVerifier.create(
                        reactor.core.publisher.Mono
                                .fromCallable(zabbixService::getAllLatestMetrics)
                                .repeatWhen(flux -> flux.delayElements(Duration.ofMillis(500)))
                                .filter(map ->
                                        map.containsKey("Zabbix server") &&
                                        map.containsKey("Host Alpha")    &&
                                        map.containsKey("Host Beta")     &&
                                        map.values().stream().allMatch(m -> m.getCpu() > 0))
                                .next())
                .expectNextMatches(map -> {
                    assertThat(map).containsKeys("Zabbix server", "Host Alpha", "Host Beta");
                    // Host names must be correctly set in each Metrics object.
                    assertThat(map.get("Zabbix server").getHostName()).isEqualTo("Zabbix server");
                    assertThat(map.get("Host Alpha").getHostName()).isEqualTo("Host Alpha");
                    assertThat(map.get("Host Beta").getHostName()).isEqualTo("Host Beta");
                    // Distinct base values guarantee that CPU values differ between hosts.
                    double cpuServer = map.get("Zabbix server").getCpu();
                    double cpuAlpha  = map.get("Host Alpha").getCpu();
                    double cpuBeta   = map.get("Host Beta").getCpu();
                    assertThat(cpuBeta).isGreaterThan(cpuServer);
                    assertThat(cpuAlpha).isGreaterThan(cpuServer);
                    return true;
                })
                .expectComplete()
                .verify(Duration.ofSeconds(10));
    }

    /** Verifies that GET /metrics/hosts/{hostName} returns 200 for a known host. */
    @Test
    void perHostEndpointReturnsMetricsForKnownHost() {
        webTestClient.get()
                .uri(b -> b.path("/metrics/hosts/{h}").build("Host Alpha"))
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$.cpu").exists()
                .jsonPath("$.hostName").isEqualTo("Host Alpha");
    }

    /** Verifies that GET /metrics/hosts/{hostName} returns 404 for an unknown host. */
    @Test
    void perHostEndpointReturns404ForUnknownHost() {
        webTestClient.get()
                .uri(b -> b.path("/metrics/hosts/{h}").build("NonExistentHost"))
                .exchange()
                .expectStatus().isNotFound();
    }
}
