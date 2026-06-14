plugins {
    kotlin("jvm") version "1.9.24"
    application
}

group = "com.pschlup"
version = "1.0-SNAPSHOT"

kotlin {
    jvmToolchain(17)
}

application {
    mainClass.set("com.pschlup.ta.MultiAssetSoftExitTest")
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
