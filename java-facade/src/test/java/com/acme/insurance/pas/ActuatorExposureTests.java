package com.acme.insurance.pas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Loads the production application.properties (only the datasource is swapped
 * for H2) and verifies that /manage/health is the only actuator endpoint served.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = {
        "spring.config.location=file:src/main/resources/application.properties",
        "spring.datasource.url=jdbc:h2:mem:actuatordb;DB_CLOSE_DELAY=-1;MODE=DB2",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.tomcat.validation-query=SELECT 1",
        "logging.file="
})
@AutoConfigureMockMvc
public class ActuatorExposureTests {

    private static final String[] SENSITIVE_ENDPOINTS = {
            "heapdump", "env", "configprops", "trace", "dump", "mappings",
            "beans", "metrics", "autoconfig", "info", "loggers", "auditevents"
    };

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void healthIsExposed() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }

    @Test
    public void sensitiveEndpointsAreNotExposed() throws Exception {
        for (String endpoint : SENSITIVE_ENDPOINTS) {
            mockMvc.perform(get("/manage/" + endpoint))
                    .andExpect(status().isNotFound());
        }
    }
}
