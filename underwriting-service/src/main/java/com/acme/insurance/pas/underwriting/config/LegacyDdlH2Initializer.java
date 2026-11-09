package com.acme.insurance.pas.underwriting.config;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import javax.sql.DataSource;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.init.ResourceDatabasePopulator;
import org.springframework.stereotype.Component;
import org.springframework.util.StreamUtils;

/**
 * Local/test only: creates the schema in H2 from the unmodified DB2 DDL
 * ({@code sql/ddl/create-tables.sql}, packaged as {@code legacy-ddl/create-tables.sql}) by
 * dropping the DB2 storage clauses H2 does not understand. Never enabled against DB2.
 */
@Component
@ConditionalOnProperty(name = "pas.underwriting.h2.init-legacy-ddl", havingValue = "true")
public class LegacyDdlH2Initializer implements InitializingBean {

    private final DataSource dataSource;
    private final String seedScript;

    public LegacyDdlH2Initializer(DataSource dataSource,
            @Value("${pas.underwriting.h2.seed-script:}") String seedScript) {
        this.dataSource = dataSource;
        this.seedScript = seedScript;
    }

    @Override
    public void afterPropertiesSet() throws IOException {
        JdbcTemplate jdbc = new JdbcTemplate(dataSource);
        jdbc.execute("CREATE SCHEMA IF NOT EXISTS ACMEINS");
        for (String statement : toH2(read(new ClassPathResource("legacy-ddl/create-tables.sql")))) {
            jdbc.execute(statement);
        }
        ResourceDatabasePopulator populator = new ResourceDatabasePopulator(new ClassPathResource("h2/claims-stub.sql"));
        if (!seedScript.isBlank()) {
            populator.addScript(new ClassPathResource(seedScript));
        }
        populator.execute(dataSource);
    }

    static List<String> toH2(String db2Ddl) {
        List<String> out = new ArrayList<>();
        String noComments = db2Ddl.replaceAll("--[^\\n]*", "");
        for (String raw : noComments.split(";")) {
            String stmt = raw.strip();
            String upper = stmt.toUpperCase();
            if (stmt.isEmpty() || upper.startsWith("CREATE TABLESPACE") || upper.equals("COMMIT")) {
                continue;
            }
            out.add(stmt.replaceAll("(?i)\\)\\s*IN\\s+ACMEDB\\.\\w+", ")")
                    .replaceAll("(?i)CURRENT TIMESTAMP", "CURRENT_TIMESTAMP")
                    .replaceAll("(?i)CURRENT DATE", "CURRENT_DATE"));
        }
        return out;
    }

    private static String read(Resource resource) throws IOException {
        return StreamUtils.copyToString(resource.getInputStream(), StandardCharsets.UTF_8);
    }
}
