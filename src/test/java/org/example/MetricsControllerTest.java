package org.example;

import org.example.model.Metrics;
import org.example.service.ZabbixService;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.reactive.AutoConfigureWebTestClient;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.reactive.server.WebTestClient;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureWebTestClient
class MetricsControllerTest {

    @Autowired
    private WebTestClient webTestClient;

    @Autowired
    private ZabbixService zabbixService;

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

    @Test
    void zabbixServiceReturnsNonNullMetrics() {
        Metrics m = zabbixService.getLatestMetrics();
        assertThat(m).isNotNull();
        assertThat(m.getTimestamp()).isGreaterThan(0L);
    }
}
