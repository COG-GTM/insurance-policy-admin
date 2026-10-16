package com.acme.insurance.pas.repository;

import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.junit4.SpringRunner;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.fail;

@RunWith(SpringRunner.class)
@SpringBootTest
public class PolicyHolderPiiProtectionTests {

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Test
    public void facadeAccount_isNotAdmin() {
        String admin = jdbcTemplate.queryForObject(
                "SELECT ADMIN FROM INFORMATION_SCHEMA.USERS WHERE NAME = USER()",
                String.class);
        assertEquals("PASFACAD", jdbcTemplate.queryForObject("SELECT USER()", String.class));
        assertEquals("FALSE", admin.toUpperCase());
    }

    @Test
    public void facadeAccount_cannotReadPolicyHolderPii() {
        String[] columns = {"SSN_LAST4", "TAX_ID", "DATE_OF_BIRTH", "CREDIT_SCORE"};
        for (String column : columns) {
            try {
                jdbcTemplate.queryForList(
                        "SELECT " + column + " FROM ACMEINS.POLICY_HOLDERS");
                fail("Facade account must not read POLICY_HOLDERS." + column);
            } catch (DataAccessException expected) {
                // access denied
            }
        }
    }

    @Test
    public void facadeAccount_canReadPoliciesAndCoverages() {
        jdbcTemplate.queryForList("SELECT POLICY_NUMBER FROM ACMEINS.POLICIES");
        jdbcTemplate.queryForList("SELECT POLICY_NUMBER FROM ACMEINS.COVERAGES");
    }
}
