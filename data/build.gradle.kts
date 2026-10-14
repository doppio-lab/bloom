plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

dependencies {
    api(project(":core"))
    api(libs.exposed.core)
    api(libs.exposed.jdbc)
    api(libs.exposed.java.time)
    implementation(libs.exposed.json)
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
    testRuntimeOnly(libs.logback)
}
