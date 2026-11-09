package com.acme.insurance.pas.underwriting.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;

class LegacyDdlH2InitializerTest {

    @Test
    void stripsDb2StorageClausesAndKeepsTables() {
        String ddl = """
                -- comment; with semicolon
                CREATE TABLESPACE PASTS01 IN ACMEDB USING STOGROUP X;
                CREATE TABLE ACMEINS.T (
                    A CHAR(1),
                    TS TIMESTAMP NOT NULL DEFAULT CURRENT TIMESTAMP
                ) IN ACMEDB.PASTS01;
                COMMIT;
                """;
        List<String> statements = LegacyDdlH2Initializer.toH2(ddl);
        assertThat(statements).hasSize(1);
        assertThat(statements.get(0)).startsWith("CREATE TABLE ACMEINS.T").endsWith(")")
                .contains("CURRENT_TIMESTAMP").doesNotContain("ACMEDB");
    }
}
