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

import java.util.Properties;

import static org.junit.Assert.assertEquals;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
// Load the production application.properties explicitly (the test-resources
// application.properties would shadow it on the classpath), with only the
// datasource swapped to H2 and file logging disabled.
@TestPropertySource(
        locations = "file:src/main/resources/application.properties",
        properties = {
                "spring.datasource.url=jdbc:h2:mem:actuatordb;DB_CLOSE_DELAY=-1;MODE=DB2",
                "spring.datasource.username=sa",
                "spring.datasource.password=",
                "spring.datasource.driver-class-name=org.h2.Driver",
                "spring.datasource.initialize=true",
                "logging.file=",
                "logging.path="
        })
public class ActuatorEndpointSecurityTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void sensitiveEndpointsAreNotExposed() throws Exception {
        String[] endpoints = {
                "env", "heapdump", "dump", "trace", "configprops",
                "mappings", "loggers", "beans", "metrics", "autoconfig"
        };
        for (String name : endpoints) {
            int status = mockMvc.perform(get("/manage/" + name))
                    .andReturn().getResponse().getStatus();
            assertEquals("GET /manage/" + name + " must not be exposed",
                    404, status);
        }
    }

    @Test
    public void loggersCannotBeChangedAtRuntime() throws Exception {
        mockMvc.perform(post("/manage/loggers/ROOT")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"configuredLevel\":\"DEBUG\"}"))
                .andExpect(status().isNotFound());
    }

    @Test
    public void healthIsPublicButHidesDetails() throws Exception {
        mockMvc.perform(get("/manage/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.db").doesNotExist())
                .andExpect(jsonPath("$.diskSpace").doesNotExist());
    }

    @Test
    public void productionConfigHasNoDefaultDb2Password() throws Exception {
        Properties props = PropertiesLoaderUtils.loadProperties(
                new FileSystemResource("src/main/resources/application.properties"));
        assertEquals("${DB2_PASSWORD}", props.getProperty("spring.datasource.password"));
    }
}
