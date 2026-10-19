package com.academy.paybridge.postgres;

import org.springframework.boot.test.util.TestPropertyValues;
import org.springframework.context.ApplicationContextInitializer;
import org.springframework.context.ConfigurableApplicationContext;

/**
 * Points the application at the Docker PostgreSQL and runs it the way production does: Flyway applies
 * every migration, Hibernate only VALIDATES the schema against its entities, and nothing is seeded from H2 scripts.
 */
public class PostgresContextInitializer implements ApplicationContextInitializer<ConfigurableApplicationContext> {

    @Override
    public void initialize(ConfigurableApplicationContext context) {
        TestPropertyValues.of(
                "spring.datasource.url=" + PostgresDatabase.url(PostgresDatabase.MAIN_DB),
                "spring.datasource.username=" + PostgresDatabase.USER,
                "spring.datasource.password=" + PostgresDatabase.PASSWORD,
                "spring.datasource.driver-class-name=org.postgresql.Driver",
                "spring.jpa.hibernate.ddl-auto=validate",
                "spring.jpa.defer-datasource-initialization=false",
                "spring.sql.init.mode=never",
                "spring.flyway.enabled=true",
                "spring.flyway.locations=classpath:db/migration,classpath:db/seed-dev",
                "paybridge.reconciliation.enabled=false",
                "paybridge.settlement.enabled=false",
                "paybridge.remittance.reconciliation.enabled=false"
        ).applyTo(context);
    }
}
