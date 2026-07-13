# Spring Boot 4 Migration Scope (ESUP-2028)

Scoping document for upgrading from Spring Boot 3.5.x to Spring Boot 4, which is the blocker for resolving the bulk of the outstanding dependency vulnerabilities.

**Status:** Scoped, not started. Intended as a standalone ticket/PR — do not mix with routine dependency bumps.

---

## Table of Contents

1. [Why This Is Needed](#1-why-this-is-needed)
2. [The Version Lever](#2-the-version-lever)
3. [Matched Version Set](#3-matched-version-set)
4. [What Boot 4 Clears](#4-what-boot-4-clears)
5. [Code-Level Breaking Changes](#5-code-level-breaking-changes)
6. [Risk Ranking](#6-risk-ranking)
7. [Recommended Approach](#7-recommended-approach)
8. [Effort Estimate](#8-effort-estimate)

---

## 1. Why This Is Needed

The scheduled OWASP Dependency-Check and Trivy scans (GitHub code-scanning) report ~885 open alerts, which collapse to **85 distinct CVEs** across ~8 libraries (the count is inflated because Dependency-Check raises one alert per CVE × jar, so sibling modules — 17 netty jars, 14 spring jars — carry identical CVE sets).

Roughly **70 of those 85 CVEs** sit in `spring-framework`, `tomcat-embed`, `jackson-databind`, `spring-security`, `netty` and `postgresql`, and have **no fix reachable on the Spring Boot 3.5.x line**. They are only fixable by moving to Spring Boot 4.

## 2. The Version Lever

The vulnerable library versions are **not** pinned by the `hmpps-kotlin-spring-boot-starter` library — they are pinned by the **`uk.gov.justice.hmpps.gradle-spring-boot` gradle plugin**, which bundles `spring-boot-gradle-plugin` and forces a Spring Boot platform onto the whole graph.

- Plugin **9.7.1 → Spring Boot 3.5.14** (spring 6.2.18, tomcat 10.1.54, jackson 2.21.2, netty 4.1.132). **9.7.1 is the last plugin release on the 3.5.x line.**
- Plugin **10.5.7 → Spring Boot 4.0.7** (spring 7.0.8, tomcat 11.0.22, spring-security 7.0.6, jackson 3.1.4, netty 4.2.15).

Because the plugin's platform wins over the starter BOM, bumping the starter alone does **not** move the vulnerable libraries. The plugin bump is the only lever, and it crosses the Boot 3 → Boot 4 major boundary.

## 3. Matched Version Set

All of these must move together:

| Item | Current | Boot 4 target | Notes |
|---|---|---|---|
| `uk.gov.justice.hmpps.gradle-spring-boot` plugin | 9.7.1 | **10.5.7** | Carries `spring-boot-gradle-plugin` 4.0.7 — the real lever |
| `hmpps-kotlin-spring-boot-starter` / `-autoconfigure` / `-starter-test` | 1.8.2 | **2.5.0** | Boot 4.0.x matched set; absorbs Spring Security 7 config |
| `hmpps-sqs-spring-boot-starter` | 5.6.3 | **7.4.0** | Boot 4.0.7 |
| `io.github.resilience4j:resilience4j-spring-boot3` | 2.2.0 | **`resilience4j-spring-boot4` 2.4.0** | ⚠️ artifact renamed `-boot3` → `-boot4` |
| `org.springdoc:springdoc-openapi-starter-webmvc-ui` | 2.8.8 | **3.0.3** | Major bump for Boot 4 |
| `net.javacrumbs.shedlock:shedlock-spring` (+ jdbc provider) | 6.9.2 | **7.7.0** | Boot 4 line |
| AWS SDK (`s3`/`sts`/`rekognition`/`netty-nio-client`) | 2.47.5 | (unchanged) | Boot-independent |

## 4. What Boot 4 Clears

Spring Boot 4.0.7's BOM pulls: **spring-framework 7.0.8, spring-security 7.0.6, tomcat 11.0.22, jackson 3.1.4, netty 4.2.15.Final**. Cross-referenced against the scan's fix versions:

- ✅ **netty** 4.2.15 ≥ 4.2.13 fix line → clears 5 critical + 17 high
- ✅ **tomcat** 11.0.22 → clears 5 critical + 4 high (fixes were 11.0.22 / 11.0.23)
- ✅ **spring-framework** 7.0.8 → clears the critical + highs (no 6.2.x fix existed)
- ✅ **spring-security** 7.0.6, **jackson** 3.1.4 → cleared
- ⚠️ **postgresql**: Boot 4.0.7 ships **42.7.11**, but CVE-2026-54291 needs **42.7.12** → **still requires an explicit `postgresql` version override** after the Boot 4 bump.

`swagger-ui` mediums are dev-only (springdoc UI) and already partly suppressed.

## 5. Code-Level Breaking Changes

1. **Jackson 2 → 3 (`com.fasterxml.jackson` → `tools.jackson`)** — the main mechanical work.
   - **13 files** touch databind/core/module and need the package rename: 3× custom `JsonDeserializer` (Jackson 3 has signature changes — not pure find/replace), 3× `ObjectMapper`, the Kotlin module (`jacksonObjectMapper()` / `registerKotlinModule` / `readValue` → `tools.jackson.module.kotlin`), `JsonNode`, `JsonParser` / `JsonToken`.
   - **3 files** are annotation-only (`@JsonProperty`, `@JsonCreator`, `@JsonValue`, `@JsonIgnoreProperties`) — these **stay** on `com.fasterxml.jackson.annotation`, no change.
2. **resilience4j artifact rename** — update `build.gradle.kts` to `resilience4j-spring-boot4`; verify the circuit-breaker / retry annotations still resolve.
3. **Spring Security 7** — **low app risk**: this app has no security config of its own (it lives in the `hmpps-kotlin` autoconfigure library). Bumping the starter to 2.5.0 absorbs the Security 7 DSL changes.
4. **springdoc 3 / Tomcat 11 / Servlet 6.1** — check `OpenApiConfiguration.kt` and the webflux + webmvc-ui mix; usually low-touch.
5. **Tests** — verification leans on CI (testcontainers cannot run locally in this setup). Re-check the wiremock / swagger-parser test dependencies against Boot 4.

## 6. Risk Ranking

| Risk | Level | Why |
|---|---|---|
| Jackson 3 migration (13 files, custom deserializers) | **Medium** | API signature changes, not just imports; touches serialization of DTOs / entities / domain events |
| resilience4j boot4 starter behaviour | Low–Medium | Artifact rename; autoconfig differences possible |
| springdoc 3 OpenAPI rendering | Low | Config-level, dev-only surface |
| Spring Security 7 | Low | Owned by the hmpps library, not the app |
| postgresql residual CVE | Low | One explicit pin to 42.7.12+ |
| Cannot run tests locally | Process | Migration correctness rides on CI |

## 7. Recommended Approach

1. Do it in a **git worktree spike** off the current branch, as its own ticket/PR — not mixed with routine dependency bumps.
2. Order of operations: bump the plugin 9.7.1 → 10.5.7 first, let it fail, then walk the compile errors: resilience4j artifact rename → Jackson 3 imports → springdoc / shedlock → add the postgresql 42.7.12 pin.
3. Lean on CI for the testcontainers suite; smoke-test the check-in serialization paths (domain events + DTOs), which are the heaviest Jackson users.

## 8. Effort Estimate

Roughly a **1–3 day focused spike** — dominated by the Jackson 3 pass and CI iteration, not the version bumps themselves.
