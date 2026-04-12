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
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Uses DEFINED_PORT so the ZabbixService's WebClient can reach the in-process
 * dummy Zabbix controllers (configured at localhost:8080 in application.properties).
 * With RANDOM_PORT the dummy endpoints would be unreachable at that URL.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.DEFINED_PORT)
@AutoConfigureWebTestClient
class MetricsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ZabbixService zabbixService;

    /** Verifies the JSON array structure returned by GET /metrics. */
    @Test
    void metricsEndpointReturnsJson() {
        webTestClient.get()
                .uri("/metrics")
                .exchange()
                .expectStatus().isOk()
                .expectBody()
                .jsonPath("$[0].hostName").exists()
                .jsonPath("$[0].cpu").exists()
                .jsonPath("$[0].ram").exists()
                .jsonPath("$[0].disk").exists()
                .jsonPath("$[0].networkIn").exists()
                .jsonPath("$[0].networkOut").exists()
                .jsonPath("$[0].timestamp").exists();
    }

    /** Verifies that the service returns a non-null snapshot with a valid timestamp. */
    @Test
    void zabbixServiceReturnsNonNullMetrics() {
        Metrics m = zabbixService.getLatestMetrics();
        assertThat(m).isNotNull();
        assertThat(m.getTimestamp()).isGreaterThan(0L);
    }

    /** Verifies that all configured hosts are returned. */
    @Test
    void metricsEndpointReturnsAllHosts() {
        webTestClient.get()
                .uri("/metrics")
                .exchange()
                .expectStatus().isOk()
                .expectBodyList(Metrics.class)
                .hasSize(3);
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
}
