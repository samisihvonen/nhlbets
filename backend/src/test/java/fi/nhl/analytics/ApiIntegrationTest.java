package fi.nhl.analytics;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.datasource.url=jdbc:h2:mem:api-integration;DB_CLOSE_DELAY=-1",
        "analytics.scheduler-enabled=false"})
class ApiIntegrationTest {
    @LocalServerPort int port;
    @Autowired TestRestTemplate http;
    @Test void healthAndEmptyMatchdayPlanAreAvailable() {
        var health = http.getForEntity("http://localhost:" + port + "/api/health", String.class);
        var plan = http.getForEntity("http://localhost:" + port + "/api/matchday-plan", String.class);
        assertEquals(200, health.getStatusCode().value()); assertTrue(health.getBody().contains("UP"));
        assertEquals(200, plan.getStatusCode().value()); assertTrue(plan.getBody().contains("\"gatesPassed\":false"));
        assertTrue(plan.getBody().contains("\"plannedStakeCents\":0"));
    }
}
