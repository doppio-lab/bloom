plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    implementation(libs.exposed.core)
    implementation(libs.exposed.jdbc)
    implementation(libs.exposed.java.time)
    implementation(libs.postgres)
    implementation(libs.hikari)
    implementation(libs.flyway.core)
    implementation(libs.flyway.postgres)
    api(libs.ktor.client.core)
    implementation(libs.ktor.client.cio)
    implementation(libs.ktor.client.content.negotiation)
    implementation(libs.ktor.serialization.json)
    implementation(libs.jsoup)
    api(libs.coroutines.core)
    testImplementation(kotlin("test"))
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.ktor.client.mock)
    testImplementation(testFixtures(project(":core")))
    testRuntimeOnly(libs.logback)
}

// Testcontainers needs a Docker socket. OrbStack does not create /var/run/docker.sock, so without this a plain
// `./gradlew test` fails with "Could not find a valid Docker environment". An explicit DOCKER_HOST always wins.
tasks.withType<Test>().configureEach {
    val orbstack = File(System.getProperty("user.home"), ".orbstack/run/docker.sock")
    if (System.getenv("DOCKER_HOST") == null && !File("/var/run/docker.sock").exists() && orbstack.exists()) {
        environment("DOCKER_HOST", "unix://${orbstack.path}")
        environment("TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE", "/var/run/docker.sock")
    }
}
