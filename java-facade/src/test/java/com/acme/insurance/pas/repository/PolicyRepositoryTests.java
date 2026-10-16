package com.acme.insurance.pas.repository;

import com.acme.insurance.pas.model.Coverage;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.jdbc.Sql;
import org.springframework.test.context.junit4.SpringRunner;

import java.util.List;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

@RunWith(SpringRunner.class)
@SpringBootTest
public class PolicyRepositoryTests {

    @Autowired
    private PolicyRepository policyRepository;

    @Test
    @Sql("/sql/non-exposed-policy.sql")
    public void nonExposedPolicyIsNotReturned() {
        assertNull(policyRepository.findByPolicyNumber("POL-00000099"));
    }

    @Test
    @Sql("/sql/non-exposed-policy.sql")
    public void coveragesForNonExposedPolicyAreNotReturned() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000099");
        assertTrue(coverages.isEmpty());
    }

    @Test
    @Sql("/sql/non-exposed-policy.sql")
    public void exposedPolicyCoveragesAreStillReturned() {
        List<Coverage> coverages = policyRepository.findCoveragesByPolicyNumber("POL-00000001");
        assertEquals(2, coverages.size());
    }
}
