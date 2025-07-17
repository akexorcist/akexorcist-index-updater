@file:OptIn(ExperimentalWasmDsl::class, ExperimentalKotlinGradlePluginApi::class)

import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.ExperimentalWasmDsl
import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    alias(libs.plugins.kotlin.multiplatform)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.shadow)
}

val jvmMainClass = "Main_jvmKt"

kotlin {
    jvm {
        binaries {
            executable {
                mainClass.set(jvmMainClass)
            }
        }
        tasks.getting(Jar::class) {
            duplicatesStrategy = DuplicatesStrategy.EXCLUDE
            doFirst {
                manifest {
                    attributes["Main-Class"] = jvmMainClass
                }

                from(configurations["jvmRuntimeClasspath"].map { if (it.isDirectory) it else zipTree(it) })
            }
        }
    }

    sourceSets {
        commonMain.dependencies {
            implementation(libs.kotlinx.serialization.json)
            implementation(libs.ktor.server.cio)
            implementation(libs.ktor.server.sse)
            implementation(libs.ktor.server.websockets)
            implementation(libs.ktor.server.content.negotiation)
            implementation(libs.ktor.serialization.json)
            implementation(libs.ktor.client.logging)
            implementation(libs.ktor.client.content.negotiation)
            implementation(libs.dotenv)
            implementation(libs.ktor.client.cio)
            implementation(libs.java.jwt)
            implementation(libs.koin.core)
            implementation(libs.koin.ktor)
            implementation(libs.koin.logger.slf4j)
        }

        commonTest {
            dependencies {
                implementation(libs.kotlin.test)
                implementation(libs.ktor.server.test.host)
                implementation(libs.kotlinx.coroutines.test)
                implementation(libs.kotest.assertions.json)
                implementation(libs.kotest.assertions.core)
                implementation(libs.kotest.framework.engine)
            }
        }

        jvmMain.dependencies {
            implementation(libs.slf4j.nop)
        }

        jvmTest {
            dependencies {
                implementation(libs.mockk)
                implementation(libs.slf4j.simple)
                implementation(libs.kotest.runner.junit5)
            }
        }
    }
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(17))
    }
}

tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile> {
    compilerOptions.jvmTarget.set(JvmTarget.JVM_17)
}

tasks.withType<ShadowJar> {
    archiveBaseName.set("akexorcist-index-updater")
    archiveVersion.set((findProperty("appVersion") as? String)?.removePrefix("v") ?: "1.0.0")
    archiveClassifier.set("all")

    manifest {
        attributes(mapOf("Main-Class" to jvmMainClass))
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}
