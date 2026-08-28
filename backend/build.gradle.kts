plugins {
    kotlin("jvm") version "2.1.20"
    kotlin("plugin.spring") version "2.1.20"
    id("org.springframework.boot") version "3.4.5"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.foliage"
version = "0.1.0"

java {
    toolchain { languageVersion = JavaLanguageVersion.of(23) }
}

repositories {
    mavenCentral()
    // NetCDF-Java is published only to Unidata's own repository. Note this
    // needs a JVM whose cacerts carries Sectigo Public Server Authentication
    // Root R46 -- Temurin does, Oracle's bundle does not. See ADR-0006.
    maven {
        name = "unidata"
        url = uri("https://artifacts.unidata.ucar.edu/all/")
        content { includeGroup("edu.ucar") }
    }
}

dependencies {
    // Web + ops
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")

    // Persistence: plain JDBC + Flyway. No JPA -- the hot paths are batched
    // multi-row INSERTs and wide range scans, neither of which an ORM helps with.
    // See ADR-0003.
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.flywaydb:flyway-core")
    implementation("org.flywaydb:flyway-mysql")
    runtimeOnly("com.mysql:mysql-connector-j")

    // Ingest concurrency
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core")

    // Geospatial. H3 is the spatial index; JTS is bootstrap-only. See ADR-0002.
    implementation("com.uber:h3:4.1.1")
    implementation("org.locationtech.jts:jts-core:1.20.0")

    // Read-path caching for forecast snapshots
    implementation("com.github.ben-manes.caffeine:caffeine")

    // GRIB2 decoding for NOAA HRRR -- JVM-native, no shelling out to wgrib2.
    implementation("edu.ucar:grib:5.10.0")
    implementation("edu.ucar:cdm-core:5.10.0")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    // No Testcontainers: this environment has no Docker, so a container-backed
    // test could never run here. External services are tested against captured
    // fixtures instead, and database tests run against a real schema when one
    // is configured. See docs/testing.md.
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

tasks.withType<Test> { useJUnitPlatform() }

// Convenience for spikes: prints the runtime classpath so a scratch program
// can be run against the resolved dependency tree.
tasks.register("printRuntimeClasspath") {
    val cp = configurations.runtimeClasspath
    doLast { println(cp.get().asPath) }
}
