/* Copyright (C) 2019 Jonas Herzig <me@johni0702.de>
 *
 * This program is free software: you can redistribute it and/or modify
 * it under the terms of the GNU General Public License as published by
 * the Free Software Foundation, either version 3 of the License, or
 * (at your option) any later version.
 *
 * This program is distributed in the hope that it will be useful,
 * but WITHOUT ANY WARRANTY; without even the implied warranty of
 * MERCHANTABILITY or FITNESS FOR A PARTICULAR PURPOSE.  See the
 * GNU General Public License for more details.
 *
 * You should have received a copy of the GNU General Public License
 * along with this program.  If not, see <http://www.gnu.org/licenses/>.
 */

plugins {
    groovy
    kotlin("jvm") version("2.0.0")
    `kotlin-dsl`
    `java-gradle-plugin`
    `maven-publish`
}

group = "com.github.SkyHanniStudios"
version = "0.8.1"
val githubProjectName = "SkyHanni-Preprocessor"

kotlin {
    jvmToolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

val kotestVersion = "4.2.2"

java {
    withSourcesJar()
}

repositories {
    mavenLocal()
    mavenCentral()
    gradlePluginPortal()
    maven(url = "https://jitpack.io/")
    maven(url = "https://maven.fabricmc.net/")
    maven(url = "https://maven.deftu.dev/releases/")
}

dependencies {
    implementation(gradleApi())
    implementation(localGroovy())
    implementation("com.github.SkyHanniStudios:SkyHanni-Remap:b50001e883")
    implementation("net.fabricmc:mapping-io:0.6.1")
    testImplementation("io.kotest:kotest-runner-junit5-jvm:$kotestVersion")
    testImplementation("io.kotest:kotest-assertions-core-jvm:$kotestVersion")
}

gradlePlugin {
    plugins {
        register("PreprocessPlugin") {
            id = "$group.$githubProjectName"
            implementationClass = "com.replaymod.gradle.preprocess.PreprocessPlugin"
        }

//        register("RootPreprocessPlugin") {
//            id = "$group.$githubProjectName-root"
//            implementationClass = "com.replaymod.gradle.preprocess.RootPreprocessPlugin"
//        }
    }
}

publishing {
    publications {
        create<MavenPublication>("plugin") {
            groupId = "$group"
            artifactId = githubProjectName
            version = "${project.version}"

            from(components["java"])
        }
    }
}

tasks {
    withType<Test> {
        useJUnitPlatform()
    }

    jar {
        manifest {
            attributes["Implementation-Version"] = version
        }
    }
}
