package com.acme.insurance.pas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.embedded.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

/**
 * Boots the facade with the production application.properties (H2 swapped in
 * for DB2) and checks what the actuator exposes to anonymous callers.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.location=file:src/main/resources/application.properties",
        "spring.datasource.url=jdbc:h2:mem:actuatordb;DB_CLOSE_DELAY=-1;MODE=DB2",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.initialize=true",
        "spring.datasource.tomcat.validation-query=SELECT 1",
        "logging.file="
})
public class ActuatorEndpointsTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    private void assertStatus(HttpStatus expected, String path) {
        HttpStatus actual = restTemplate
                .getForEntity("http://localhost:" + port + path, String.class)
                .getStatusCode();
        assertEquals("GET " + path, expected, actual);
    }

    @Test
    public void productionPropertiesAreLoaded() {
        assertEquals("/manage", environment.getProperty("management.context-path"));
        assertNull(environment.getProperty("management.security.enabled"));
    }

    @Test
    public void memoryConfigAndHeaderEndpointsAreDisabled() {
        assertStatus(HttpStatus.NOT_FOUND, "/manage/heapdump");
        assertStatus(HttpStatus.NOT_FOUND, "/manage/env");
        assertStatus(HttpStatus.NOT_FOUND, "/manage/env/spring.datasource.password");
        assertStatus(HttpStatus.NOT_FOUND, "/manage/configprops");
        assertStatus(HttpStatus.NOT_FOUND, "/manage/trace");
        assertStatus(HttpStatus.NOT_FOUND, "/manage/dump");
    }

    @Test
    public void remainingSensitiveEndpointsRequireAuthentication() {
        assertStatus(HttpStatus.UNAUTHORIZED, "/manage/beans");
        assertStatus(HttpStatus.UNAUTHORIZED, "/manage/mappings");
        assertStatus(HttpStatus.UNAUTHORIZED, "/manage/metrics");
        assertStatus(HttpStatus.UNAUTHORIZED, "/manage/autoconfig");
        assertStatus(HttpStatus.UNAUTHORIZED, "/manage/loggers");
    }

    @Test
    public void healthAndPolicyApiStayAnonymous() {
        assertStatus(HttpStatus.OK, "/manage/health");
        assertStatus(HttpStatus.OK, "/api/v1/policies/POL-00000001");
    }
}
