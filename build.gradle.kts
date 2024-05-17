import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.8.10"
}

group = "com.magicghostvu"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}
val log4jVersion = "2.17.2"
dependencies {

    api("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.6.4")
    implementation("org.slf4j:slf4j-api:1.7.25")
    runtimeOnly("org.apache.logging.log4j:log4j-slf4j-impl:$log4jVersion")
    runtimeOnly("org.apache.logging.log4j:log4j-core:$log4jVersion")
}

/*tasks.test {
    useJUnitPlatform()
}*/

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "1.8"
}