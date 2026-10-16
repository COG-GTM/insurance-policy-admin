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

/**
 * With no tokens configured the facade must fail closed:
 * every /api request is rejected, even ones bearing a token.
 */
@RunWith(SpringRunner.class)
@SpringBootTest(properties = "pas.security.api-tokens=")
@AutoConfigureMockMvc
public class ApiTokenFailClosedTests {

    @Autowired
    private MockMvc mockMvc;

    @Test
    public void bearerToken_isRejectedWhenNoTokensConfigured() throws Exception {
        mockMvc.perform(get("/api/v1/policies/POL-00000001")
                        .header("Authorization", "Bearer test-token-1"))
                .andExpect(status().isUnauthorized());
    }
}
