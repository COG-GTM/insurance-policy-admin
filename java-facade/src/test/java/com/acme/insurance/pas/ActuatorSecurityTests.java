package com.acme.insurance.pas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.support.PropertiesLoaderUtils;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.RequestBuilder;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Properties;

import static org.hamcrest.Matchers.is;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Runs against the production application.properties (the test-resources copy
 * shadows it on the classpath), with only the datasource swapped to H2 and
 * file logging turned off.
 */
@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(
        locations = "file:src/main/resources/application.properties",
        properties = {
                "spring.datasource.url=jdbc:h2:mem:actuatordb;DB_CLOSE_DELAY=-1;MODE=DB2",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.initialize=true",
                "spring.datasource.tomcat.validation-query=SELECT 1",
                "logging.file="
        })
public class ActuatorSecurityTests {

    private static final String PROD_PROPERTIES = "src/main/resources/application.properties";

    private static final String[] SENSITIVE_ENDPOINTS = {
            "heapdump", "env", "env/spring.datasource.password", "trace", "logfile",
            "loggers", "dump", "mappings", "beans", "configprops", "metrics",
            "autoconfig", "auditevents", "info"
    };

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void sensitiveEndpointsAreNotServedAnonymously() throws Exception {
        for (String name : SENSITIVE_ENDPOINTS) {
            assertBlocked("GET /manage/" + name, get("/manage/" + name));
        }
    }

    @Test
    public void loggerLevelsCannotBeChanged() throws Exception {
        assertBlocked("POST /manage/loggers/org.springframework.jdbc",
                post("/manage/loggers/org.springframework.jdbc")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"TRACE\"}"));
    }

    @Test
    public void healthIsPublicWithoutDetails() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")))
                .andExpect(jsonPath("$.db").doesNotExist())
                .andExpect(jsonPath("$.diskSpace").doesNotExist());
    }

    @Test
    public void policyApiStillWorks() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.policyNumber", is("POL-00000001")));
        mockMvc.perform(get("/api/v1/policies/POL-00000001/coverages"))
                .andExpect(status().isOk());
    }

    @Test
    public void productionConfigHasNoDefaultDb2Password() throws Exception {
        Properties props = PropertiesLoaderUtils.loadProperties(new FileSystemResource(PROD_PROPERTIES));
        assertEquals("${DB2_PASSWORD}", props.getProperty("spring.datasource.password"));
        assertEquals("true", props.getProperty("management.security.enabled"));
        assertEquals("false", props.getProperty("endpoints.enabled"));

        String dockerfile = new String(Files.readAllBytes(Paths.get("Dockerfile")), StandardCharsets.UTF_8);
        assertFalse("Dockerfile must not bake in a DB2 password",
                dockerfile.matches("(?s).*ENV\\s+DB2_PASSWORD.*"));
    }

    private void assertBlocked(String label, RequestBuilder request) throws Exception {
        int code = mockMvc.perform(request).andReturn().getResponse().getStatus();
        assertTrue(label + " returned " + code + "; expected 401 or 404", code == 401 || code == 404);
    }
}
