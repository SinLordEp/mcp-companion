// Build logic of the end-to-end sandbox (runIdeForMcpE2e): task and argument classes that the configuration cache can
// serialize. Only the Gradle API is used; the IntelliJ Platform Gradle Plugin is wired in the root build script.
plugins {
    `kotlin-dsl`
}

repositories {
    mavenCentral()
}

dependencies {
    testImplementation("junit:junit:4.13.2")
}

// buildSrc's own tests are not part of the root build's `check`; run them whenever the build logic is rebuilt (a
// finalizer, because the Kotlin test compilation needs the jar).
tasks.jar {
    finalizedBy(tasks.test)
}
