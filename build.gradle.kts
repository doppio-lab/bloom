plugins {
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ktor) apply false
}

subprojects {
    plugins.withId("org.jetbrains.kotlin.jvm") {
        extensions.configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
            jvmToolchain(21)
            compilerOptions {
                allWarningsAsErrors.set(true)
                extraWarnings.set(true)
                freeCompilerArgs.add("-Xjsr305=strict")
            }
        }
        tasks.withType<Test>().configureEach { useJUnitPlatform() }
    }
}
