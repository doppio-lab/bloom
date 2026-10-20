plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ktor)
}

application {
    mainClass.set("lab.doppio.bloom.app.ApplicationKt")
}

dependencies {
    implementation(project(":agents"))
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.server.auth)
    implementation(libs.ktor.server.call.logging)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.rate.limit)
    implementation(libs.ktor.serialization.json)
    implementation(libs.angus.mail)
    implementation(libs.jsoup)
    implementation(libs.logback)
    testImplementation(kotlin("test"))
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.testcontainers.postgresql)
    testImplementation(libs.coroutines.test)
}
