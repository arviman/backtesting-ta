plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "com.arviman"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.arviman.ta.OpenDriveFadeTest")
}

tasks.named<JavaExec>("run") {
    if (project.hasProperty("mainClass")) {
        mainClass.set(project.property("mainClass") as String)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib-jdk8"))
    implementation("com.opencsv:opencsv:4.1")

    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testImplementation("com.google.truth:truth:1.0")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.test {
    useJUnitPlatform()
}
