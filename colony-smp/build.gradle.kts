plugins {
    java
}

group = "com.colonysmp"
version = "2.9.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Built and tested against the exact Paper 1.21.11 API
    compileOnly("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")

    // The simulation cores (com.colonysmp.core, .civ, .txn) are plain Java and are unit-tested without a server
    testImplementation("io.papermc.paper:paper-api:1.21.11-R0.1-SNAPSHOT")
    // Paper ships SQLite at run time; the journal and migration tests need it on the test class path
    testImplementation("org.xerial:sqlite-jdbc:3.49.1.0")
    testImplementation(platform("org.junit:junit-bom:5.10.2"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    toolchain.languageVersion.set(JavaLanguageVersion.of(21))
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release.set(21)
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName.set("ColonySMP-${project.version}.jar")
}
