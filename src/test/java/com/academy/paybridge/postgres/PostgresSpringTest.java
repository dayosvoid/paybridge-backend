package com.academy.paybridge.postgres;

import org.junit.jupiter.api.Tag;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ContextConfiguration;

import java.lang.annotation.Documented;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * A Spring Boot test against a real PostgreSQL (Docker). Skipped by a plain "mvnw test";
 * run with "mvnw test -Ppostgres".
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
@Documented
@Tag("postgres")
@SpringBootTest
@ContextConfiguration(initializers = PostgresContextInitializer.class)
public @interface PostgresSpringTest {}
