/*  _____ _
 * |_   _| |_  _ _ ___ ___ _ __  __ _
 *   | | | ' \| '_/ -_) -_) '  \/ _` |_
 *   |_| |_||_|_| \___\___|_|_|_\__,_(_)
 *
 * Threema for Android
 * Copyright (c) 2014-2025 Threema GmbH
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU Affero General Public License, version 3,
 * as published by the Free Software Foundation.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE. See the
 * GNU Affero General Public License for more details.
 *
 * You should have received a copy of the GNU Affero General Public License
 * along with this program. If not, see <https://www.gnu.org/licenses/>.
 */

import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.tasks.KotlinJvmCompile

buildscript {
    repositories {
        google()
        mavenCentral()
        flatDir { dir("app/libs") }
    }
    dependencies {
        classpath(libs.kotlin.gradle)
        classpath(libs.android.gradle)

        // Huawei agconnect plugin
        classpath("com.huawei.agconnect:agcp-1.9.1.303")
        classpath("com.huawei.agconnect:agconnect-crash-symbol-lib-1.9.1.301")
        classpath("com.huawei.agconnect:agconnect-apms-plugin-1.6.2.300")
        classpath("com.huawei.agconnect:agconnect-core-1.9.1.301@aar")
    }
}

plugins {
    alias(libs.plugins.jacoco)
    alias(libs.plugins.sonarqube)
    alias(libs.plugins.ktlint)
    alias(libs.plugins.compose.compiler) apply false
}

allprojects {
    repositories {
        google()
        mavenCentral()
        maven("https://jitpack.io")
        flatDir { dir("libs") }

        // Huawei
        exclusiveContent {
            forRepository {
                maven("https://developer.huawei.com/repo/")
            }
            filter {
                // Only matching dependencies will be installed from this repository.
                includeGroup("com.huawei.hms")
                includeGroup("com.huawei.android.hms")
                includeGroup("com.huawei.hmf")
            }
        }
    }

    group = "ch.threema"
}

tasks.wrapper {
    distributionType = Wrapper.DistributionType.ALL
}

sonarqube {
    properties {
        property("sonar.projectKey", "android-client")
        property("sonar.projectName", "Threema for Android")
    }
}

jacoco {
    toolVersion = "0.8.7"
}

subprojects {
    apply(plugin = "org.jlleitschuh.gradle.ktlint")

    tasks.withType<KotlinJvmCompile>().configureEach {
        compilerOptions {
            jvmTarget.set(JvmTarget.JVM_11)
            freeCompilerArgs.add("-Xjvm-default=all")
        }
    }

    configurations.configureEach {
        resolutionStrategy {
            eachDependency {
                if (requested.group == "org.jacoco") {
                    useVersion("0.8.7")
                }
            }
        }
    }

    ktlint {
        outputToConsole = true
        android = true

        filter {
            exclude { entry ->
                entry.file.path.contains("/build/")
            }
        }
    }
}

// task to gather code coverage from multiple subprojects
// NOTE: the `JacocoReport` tasks do *not* depend on the `test` task by default. Meaning you have to ensure
// that `test` (or other tasks generating code coverage) run before generating the report.
tasks.register<JacocoReport>("codeCoverageReport") {
    // If a subproject applies the 'jacoco' plugin, add the result it to the report
    subprojects {
        val subproject = this
        plugins.withType<JacocoPlugin>()
            .configureEach {
                tasks
                    .matching { it.extensions.findByType<JacocoTaskExtension>() != null }
                    .configureEach {
                        sourceSets(
                            subproject.extensions.getByType(SourceSetContainer::class.java).getByName("main"),
                        )
                    }
            }
    }

    reports {
        xml.required = true
    }
}
