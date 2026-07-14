import org.gradle.internal.classpath.Instrumented.systemProperty
import org.gradle.kotlin.dsl.implementation

plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "10.5.7"
  kotlin("plugin.jpa") version "2.3.21"
  kotlin("plugin.spring") version "2.3.21"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  implementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-starter:2.5.0")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  // Boot 4 decoupled WebClient autoconfiguration (WebClient.Builder) from webflux into its own module;
  // required for the outbound WebClients in WebClientConfiguration (hmpps-auth, ndelius, manage-users).
  implementation("org.springframework.boot:spring-boot-starter-webclient")
  // Keep in lockstep with springdoc-openapi-starter-common pulled by hmpps-kotlin-spring-boot-autoconfigure
  // (a lower webmvc-ui against a higher common causes a SpringDoc constructor mismatch at context load).
  // 3.0.x is the Spring Framework 7 / Boot 4 line; 2.8.x is Spring 6 and is binary-incompatible here.
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:3.0.3")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("net.javacrumbs.shedlock:shedlock-spring:7.7.0")
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:7.7.0")
  implementation("uk.gov.justice.service.hmpps:hmpps-sqs-spring-boot-starter:7.4.0")
  implementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-autoconfigure:2.5.0")
  implementation("com.github.ben-manes.caffeine:caffeine")
  implementation("org.springframework.boot:spring-boot-starter-cache")

  // Resilience4j for circuit breakers and retry
  implementation("io.github.resilience4j:resilience4j-spring-boot4:2.4.0")
  implementation("org.springframework.boot:spring-boot-starter-aspectj")

  api("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:s3:2.47.5")
  implementation("software.amazon.awssdk:sts:2.47.5")
  implementation("software.amazon.awssdk:rekognition:2.47.5")
  implementation("software.amazon.awssdk:netty-nio-client:2.47.5")

  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("uk.gov.service.notify:notifications-java-client:5.2.1-RELEASE")
  // Boot 4.0.7 BOM ships postgresql 42.7.11; pin 42.7.12+ for CVE-2026-54291 (channel-binding downgrade)
  implementation("org.postgresql:postgresql:42.7.12")
  runtimeOnly("org.liquibase:liquibase-core")
  // Boot 4 extracted LiquibaseAutoConfiguration out of spring-boot-autoconfigure into this dedicated
  // module; without it migrations never run and Hibernate validates against an empty schema.
  runtimeOnly("org.springframework.boot:spring-boot-liquibase")
  implementation("com.googlecode.libphonenumber:libphonenumber:9.0.7")
  implementation("com.google.guava:guava:33.0.0-jre")

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  testImplementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-starter-test:2.5.0")
  testImplementation("org.wiremock:wiremock-standalone:3.13.0")
  testImplementation("io.swagger.parser.v3:swagger-parser:2.1.29") {
    exclude(group = "io.swagger.core.v3")
  }

  implementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
  testImplementation("org.testcontainers:testcontainers-postgresql")
  // Version managed by the Spring Boot BOM (Boot 4 → JUnit 6 / platform 6.x); pinning it stale
  // misaligns the launcher against the jupiter engine and breaks test discovery.
  testImplementation("org.junit.platform:junit-platform-launcher")
  testImplementation("org.testcontainers:testcontainers-junit-jupiter")
  testImplementation("org.awaitility:awaitility")
}

kotlin {
  jvmToolchain(21)
}

// Project-specific OWASP suppressions, applied alongside the plugin-generated
// dps-gradle-spring-boot-suppressions.xml (which is regenerated on every build).
dependencyCheck {
  suppressionFiles.add("$projectDir/owasp-suppressions.xml")
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
  }
  withType<Test> {
    useJUnitPlatform()
  }
}
