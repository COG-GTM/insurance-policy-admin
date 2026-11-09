package com.acme.insurance.pas.underwriting.persistence;

import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import org.springframework.jdbc.core.RowMapper;

/** {@code :WS-CLAIM-COUNT, :WS-TOTAL-INCURRED} fetched in 3000-CALCULATE-RISK-SCORE. */
public record ClaimsHistory(long claimCount, BigDecimal totalIncurred) {

    public static class Mapper implements RowMapper<ClaimsHistory> {
        @Override
        public ClaimsHistory mapRow(ResultSet rs, int rowNum) throws SQLException {
            return new ClaimsHistory(rs.getLong("CLAIM_COUNT"), rs.getBigDecimal("TOTAL_INCURRED"));
        }
    }
}
