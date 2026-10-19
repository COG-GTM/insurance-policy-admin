package com.acme.insurance.pas;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.junit4.SpringRunner;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@RunWith(SpringRunner.class)
@SpringBootTest
@AutoConfigureMockMvc
public class ActuatorEndpointsTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void heapdumpEndpoint_isNotExposed() throws Exception {
        mockMvc.perform(get("/manage/heapdump")).andExpect(status().isNotFound());
    }

    @Test
    public void threadDumpEndpoint_isNotExposed() throws Exception {
        mockMvc.perform(get("/manage/dump")).andExpect(status().isNotFound());
    }

    @Test
    public void envEndpoint_isNotExposed() throws Exception {
        mockMvc.perform(get("/manage/env")).andExpect(status().isNotFound());
    }

    @Test
    public void traceEndpoint_isNotExposed() throws Exception {
        mockMvc.perform(get("/manage/trace")).andExpect(status().isNotFound());
    }

    @Test
    public void configpropsEndpoint_isNotExposed() throws Exception {
        mockMvc.perform(get("/manage/configprops")).andExpect(status().isNotFound());
    }

    @Test
    public void beansEndpoint_isNotExposed() throws Exception {
        mockMvc.perform(get("/manage/beans")).andExpect(status().isNotFound());
    }

    @Test
    public void mappingsEndpoint_isNotExposed() throws Exception {
        mockMvc.perform(get("/manage/mappings")).andExpect(status().isNotFound());
    }

    @Test
    public void healthEndpoint_staysAvailable() throws Exception {
        mockMvc.perform(get("/manage/health")).andExpect(status().isOk());
    }
}
