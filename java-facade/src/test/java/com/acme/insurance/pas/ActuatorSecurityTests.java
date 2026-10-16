package com.acme.insurance.pas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.context.embedded.LocalServerPort;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.core.env.Environment;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;

@RunWith(SpringRunner.class)
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT, properties = {
        "spring.config.name=pas-facade-prod-config-test",
        "spring.config.location=file:src/main/resources/application.properties",
        "spring.datasource.url=jdbc:h2:mem:actuatortestdb;DB_CLOSE_DELAY=-1;MODE=DB2",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.initialize=true",
        "logging.file="
})
public class ActuatorSecurityTests {

    @LocalServerPort
    private int port;

    @Autowired
    private TestRestTemplate restTemplate;

    @Autowired
    private Environment environment;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    private void assertStatus(String path, HttpStatus expected) {
        ResponseEntity<String> response = restTemplate.getForEntity(url(path), String.class);
        assertEquals("Unexpected status for " + path, expected, response.getStatusCode());
    }

    @Test
    public void productionConfigIsLoaded() {
        assertEquals("/manage", environment.getProperty("management.context-path"));
        assertEquals("SELECT 1 FROM SYSIBM.SYSDUMMY1",
                environment.getProperty("spring.datasource.tomcat.validation-query"));
    }

    @Test
    public void sensitiveActuatorEndpointsRequireAuthentication() {
        assertStatus("/manage/env", HttpStatus.UNAUTHORIZED);
        assertStatus("/manage/configprops", HttpStatus.UNAUTHORIZED);
        assertStatus("/manage/beans", HttpStatus.UNAUTHORIZED);
        assertStatus("/manage/mappings", HttpStatus.UNAUTHORIZED);
        assertStatus("/manage/metrics", HttpStatus.UNAUTHORIZED);
        assertStatus("/manage/dump", HttpStatus.UNAUTHORIZED);
    }

    @Test
    public void heapdumpAndTraceEndpointsAreDisabled() {
        assertStatus("/manage/heapdump", HttpStatus.NOT_FOUND);
        assertStatus("/manage/trace", HttpStatus.NOT_FOUND);
    }

    @Test
    public void healthEndpointRemainsPublic() {
        assertStatus("/manage/health", HttpStatus.OK);
    }

    @Test
    public void policyApiRemainsAccessible() {
        assertStatus("/api/v1/policies/POL-00000001", HttpStatus.OK);
    }
}
