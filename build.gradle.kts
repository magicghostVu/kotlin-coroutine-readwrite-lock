

plugins {
    kotlin("jvm") version "1.8.10"
    id("maven-publish")
}

repositories {
    mavenCentral()
}
val log4jVersion = "2.18.0"
dependencies {
    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.slf4j:slf4j-api:1.7.36")
    /*runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:$log4jVersion")
    runtimeOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")*/
}

java {
    withSourcesJar()
}

publishing {
    publications {
        create<MavenPublication>("maven") {
            groupId = "com.magicghostvu"
            artifactId = "coroutine-rw-mutex"
            version = "0.0.1"
            from(components["kotlin"])
            artifact(tasks["sourcesJar"])
        }
    }
}

