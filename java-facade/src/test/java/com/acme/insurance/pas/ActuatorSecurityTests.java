package com.acme.insurance.pas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
@TestPropertySource(locations = "file:src/main/resources/application.properties", properties = {
        "spring.datasource.url=jdbc:h2:mem:actuatordb;DB_CLOSE_DELAY=-1;MODE=DB2",
        "spring.datasource.username=sa",
        "spring.datasource.password=",
        "spring.datasource.driver-class-name=org.h2.Driver",
        "spring.datasource.tomcat.validation-query=SELECT 1",
        "logging.file=target/actuator-test.log"
})
public class ActuatorSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void sensitiveEndpointsAreNotFound() throws Exception {
        String[] endpoints = {
                "/manage/heapdump",
                "/manage/env",
                "/manage/trace",
                "/manage/dump",
                "/manage/configprops",
                "/manage/mappings",
                "/manage/beans",
                "/manage/metrics",
                "/manage/autoconfig",
                "/manage/loggers"
        };

        for (String endpoint : endpoints) {
            mockMvc.perform(get(endpoint))
                    .andExpect(status().isNotFound());
        }
    }

    @Test
    public void changingLoggerIsNotFound() throws Exception {
        mockMvc.perform(post("/manage/loggers/ROOT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"DEBUG\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void healthEndpointRemainsAvailable() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("UP")));
    }
}
