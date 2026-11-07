plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
    `java-test-fixtures`
}

dependencies {
    api(libs.serialization.json)
    api(libs.coroutines.core)
    implementation(libs.slf4j.api)
    testImplementation(kotlin("test"))
    testImplementation(libs.coroutines.test)
}
