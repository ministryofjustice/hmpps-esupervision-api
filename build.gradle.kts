plugins {
  id("uk.gov.justice.hmpps.gradle-spring-boot") version "8.2.0"
  kotlin("plugin.jpa") version "2.1.21"
  kotlin("plugin.spring") version "2.1.21"
}

configurations {
  testImplementation { exclude(group = "org.junit.vintage") }
}

dependencies {
  implementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-starter:1.4.5")
  implementation("org.springframework.boot:spring-boot-starter-webflux")
  implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.8")
  implementation("org.springframework.boot:spring-boot-starter-oauth2-client")
  implementation("net.javacrumbs.shedlock:shedlock-spring:6.9.2")
  implementation("net.javacrumbs.shedlock:shedlock-provider-jdbc-template:6.9.2")
  implementation("uk.gov.justice.service.hmpps:hmpps-sqs-spring-boot-starter:5.4.9")
  implementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-autoconfigure:1.4.10")
  implementation("com.github.ben-manes.caffeine:caffeine")
  implementation("org.springframework.boot:spring-boot-starter-cache")

  // Resilience4j for circuit breakers and retry
  implementation("io.github.resilience4j:resilience4j-spring-boot3:2.2.0")
  implementation("org.springframework.boot:spring-boot-starter-aop")

  api("software.amazon.awssdk:s3")
  implementation("software.amazon.awssdk:s3:2.31.63")
  implementation("software.amazon.awssdk:sts:2.31.63")
  implementation("software.amazon.awssdk:rekognition:2.31.63")
  implementation("software.amazon.awssdk:netty-nio-client:2.31.63")

  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  implementation("uk.gov.service.notify:notifications-java-client:5.2.1-RELEASE")
  implementation("org.postgresql:postgresql")
  runtimeOnly("org.liquibase:liquibase-core")
  implementation("com.googlecode.libphonenumber:libphonenumber:9.0.7")
  implementation("com.google.guava:guava:33.0.0-jre")

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  testImplementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-starter-test:1.4.5")
  testImplementation("org.wiremock:wiremock-standalone:3.13.0")
  testImplementation("io.swagger.parser.v3:swagger-parser:2.1.29") {
    exclude(group = "io.swagger.core.v3")
  }

  implementation(platform("org.testcontainers:testcontainers-bom:2.0.2"))
  testImplementation("org.testcontainers:testcontainers-postgresql")
  //testImplementation("org.testcontainers:postgresql")
  testImplementation("org.junit.platform:junit-platform-launcher:1.12.2")
}

kotlin {
  jvmToolchain(21)
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
  }
}
