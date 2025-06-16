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

  implementation("org.jetbrains.kotlin:kotlin-reflect")
  implementation("org.springframework.boot:spring-boot-starter-data-jpa")
  runtimeOnly("org.postgresql:postgresql")
  implementation("com.googlecode.libphonenumber:libphonenumber:9.0.7")

  developmentOnly("org.springframework.boot:spring-boot-devtools")

  testImplementation("uk.gov.justice.service.hmpps:hmpps-kotlin-spring-boot-starter-test:1.4.5")
  testImplementation("org.wiremock:wiremock-standalone:3.13.0")
  testImplementation("io.swagger.parser.v3:swagger-parser:2.1.29") {
    exclude(group = "io.swagger.core.v3")
  }
  testRuntimeOnly("com.h2database:h2")
}

kotlin {
  jvmToolchain(21)
}

tasks {
  withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile> {
    compilerOptions.jvmTarget = org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_21
  }
}
