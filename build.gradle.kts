/*
 * Copyright (c) 2021-2026. caoccao.com Sam Cao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

object Config {
    const val GROUP_ID = "com.caoccao.javet"
    const val NAME = "Javet"
    const val VERSION = Versions.JAVET
    const val URL = "https://github.com/caoccao/Javet"

    // The casperwtf fork publishes to the Maven-layout `maven` branch, served raw by GitHub.
    object Fork {
        const val VERSION = "${Versions.JAVET}-ffm.2"
        const val URL = "https://github.com/casperwtf/Javet"
        const val REPOSITORY = "maven-repo"
    }

    object Pom {
        const val ARTIFACT_ID = "javet"
        const val DESCRIPTION =
            "Javet is Java + V8 (JAVa + V + EighT). It is an awesome way of embedding Node.js and V8 in Java."

        object Developer {
            const val ID = "caoccao"
            const val EMAIL = "sjtucaocao@gmail.com"
            const val NAME = "Sam Cao"
            const val ORGANIZATION = "caoccao.com"
            const val ORGANIZATION_URL = "https://www.caoccao.com"
        }

        object License {
            const val NAME = "APACHE LICENSE, VERSION 2.0"
            const val URL = "https://github.com/caoccao/Javet/blob/main/LICENSE"
        }

        object Scm {
            const val CONNECTION = "scm:git:git://github.com/Javet.git"
            const val DEVELOPER_CONNECTION = "scm:git:ssh://github.com/Javet.git"
        }
    }

    object Projects {
        // https://mvnrepository.com/artifact/net.bytebuddy/byte-buddy
        const val BYTE_BUDDY = "net.bytebuddy:byte-buddy:${Versions.BYTE_BUDDY}"

        // https://mvnrepository.com/artifact/com.fasterxml.jackson.core/jackson-databind
        const val JACKSON_DATABIND = "com.fasterxml.jackson.core:jackson-databind:${Versions.JACKSON_DATABIND}"

        // https://mvnrepository.com/artifact/com.caoccao.javet.buddy/javet-buddy
        const val JAVET_BUDDY = "com.caoccao.javet.buddy:javet-buddy:${Versions.JAVET_BUDDY}"

        // https://mvnrepository.com/artifact/org.eclipse.jetty.websocket/javax-websocket-server-impl
        const val JETTY_JAVAX_WEBSOCKET_SERVER_IMPL =
            "org.eclipse.jetty.websocket:javax-websocket-server-impl:${Versions.JETTY_WEBSOCKET}"

        // https://mvnrepository.com/artifact/org.eclipse.jetty.websocket/websocket-server
        const val JETTY_WEBSOCKET_SERVER = "org.eclipse.jetty.websocket:websocket-server:${Versions.JETTY_WEBSOCKET}"
    }

    object Versions {
        const val BYTE_BUDDY = "1.15.5"
        const val JACKSON_DATABIND = "2.16.0"
        const val JAVA_VERSION = "1.8"
        const val JAVA_FFM_VERSION = 25
        const val JAVET = "6.0.1"
        const val JAVET_BUDDY = "0.4.0"
        const val JETTY_WEBSOCKET = "9.4.53.v20231009"
        const val JUNIT = "5.11.3"
    }
}

val buildDir = layout.buildDirectory.get().toString()

plugins {
    java
    `java-library`
    `maven-publish`
}

group = Config.GROUP_ID
version = Config.VERSION

repositories {
    mavenCentral()
}

java {
    sourceCompatibility = JavaVersion.VERSION_1_8
    targetCompatibility = JavaVersion.VERSION_1_8
    withSourcesJar()
    withJavadocJar()
}

// Java 25+ classes (the FFM native bridge) go to META-INF/versions/25 of a multi-release jar.
val java25 by sourceSets.creating {
    java.srcDir("src/main/java25")
    compileClasspath += sourceSets.main.get().output + sourceSets.main.get().compileClasspath
}

tasks.named<JavaCompile>(java25.compileJavaTaskName) {
    options.release.set(Config.Versions.JAVA_FFM_VERSION)
}

sourceSets.test {
    runtimeClasspath += java25.output
}

dependencies {
    testImplementation(Config.Projects.BYTE_BUDDY)
    testImplementation(Config.Projects.JACKSON_DATABIND)
    testImplementation(Config.Projects.JAVET_BUDDY)
    testImplementation(Config.Projects.JETTY_JAVAX_WEBSOCKET_SERVER_IMPL)
    testImplementation(Config.Projects.JETTY_WEBSOCKET_SERVER)
//    testImplementation(files("../JavetBuddy/build/libs/javet-buddy-${Config.Versions.JAVET_BUDDY}.jar"))
}

afterEvaluate {
    tasks.withType(JavaCompile::class) {
        options.compilerArgs.add("-Xlint:unchecked")
        options.compilerArgs.add("-Xlint:deprecation")
    }
}

tasks.register<Exec>("buildJNIHeaders") {
    doFirst {
        mkdir("$buildDir/generated/tmp/jni")
    }
    workingDir("$projectDir")
    commandLine(
        "javac",
        "-h",
        "cpp/jni",
        "-d",
        "$buildDir/generated/tmp/jni",
        "src/main/java/com/caoccao/javet/interop/INodeNative.java",
        "src/main/java/com/caoccao/javet/interop/IV8Native.java",
        "src/main/java/com/caoccao/javet/interop/NodeNative.java",
        "src/main/java/com/caoccao/javet/interop/V8Native.java"
    )
}

tasks.jar {
    into("META-INF/versions/${Config.Versions.JAVA_FFM_VERSION}") {
        from(java25.output)
    }
    manifest {
        attributes["Automatic-Module-Name"] = Config.GROUP_ID
        attributes["Multi-Release"] = "true"
    }
}

tasks.named<Jar>("sourcesJar") {
    into("META-INF/versions/${Config.Versions.JAVA_FFM_VERSION}") {
        from(java25.allSource)
    }
}

testing {
    suites {
        // Configure the built-in test suite
        val test by getting(JvmTestSuite::class) {
            // Use JUnit Jupiter test framework
            useJUnitJupiter(Config.Versions.JUNIT)
        }
    }
}

tasks.test {
    useJUnitPlatform {
        excludeTags("performance")
    }
}

tasks.register<Test>("performanceTest") {
    useJUnitPlatform {
        includeTags("performance")
    }
}

tasks {
    withType<JavaCompile> {
        options.encoding = "UTF-8"
    }
    withType<Javadoc> {
        options.encoding = "UTF-8"
    }
    withType<Test> {
        systemProperty("file.encoding", "UTF-8")
        jvmArgs("--enable-native-access=ALL-UNNAMED")
        // -Djavet.ffm=false runs the suite on the JNI implementation for comparison.
        System.getProperty("javet.ffm")?.let { systemProperty("javet.ffm", it) }
    }
    // Only the upstream publication; the fork publications keep their own POM files.
    withType<GenerateMavenPom>().matching { it.name == "generatePomFileForGeneratePomPublication" }.configureEach {
        destination = file("$buildDir/libs/${Config.Pom.ARTIFACT_ID}-${Config.VERSION}.pom")
    }
}

// The fork's artifacts mirror Maven Central's split: javet without natives, one jar per native library.
val forkJavetJar = tasks.register<Jar>("forkJavetJar") {
    archiveBaseName.set(Config.Pom.ARTIFACT_ID)
    archiveVersion.set(Config.Fork.VERSION)
    destinationDirectory.set(layout.buildDirectory.dir("fork"))
    from(sourceSets.main.get().output) {
        exclude("libjavet-*")
    }
    into("META-INF/versions/${Config.Versions.JAVA_FFM_VERSION}") {
        from(java25.output)
    }
    from("LICENSE") {
        into("META-INF")
    }
    manifest {
        attributes["Automatic-Module-Name"] = Config.GROUP_ID
        attributes["Multi-Release"] = "true"
    }
}

val forkV8LinuxX8664Jar = tasks.register<Jar>("forkV8LinuxX8664Jar") {
    archiveBaseName.set("javet-v8-linux-x86_64")
    archiveVersion.set(Config.Fork.VERSION)
    destinationDirectory.set(layout.buildDirectory.dir("fork"))
    from("src/main/resources") {
        include("libjavet-v8-linux-x86_64.v.*.so")
    }
    from(files("LICENSE", "cpp/licenses/LICENSE.v8", "cpp/licenses/LICENSE.node")) {
        into("META-INF")
    }
    doFirst {
        val library = file("src/main/resources/libjavet-v8-linux-x86_64.v.${Config.VERSION}.so")
        if (!library.exists()) {
            throw GradleException("Build the native library first: $library is missing")
        }
    }
}

fun MavenPom.forkPom(pomName: String, pomDescription: String) {
    name.set(pomName)
    description.set(pomDescription)
    url.set(Config.Fork.URL)
    licenses {
        license {
            name.set(Config.Pom.License.NAME)
            url.set(Config.Pom.License.URL)
        }
    }
    developers {
        developer {
            id.set(Config.Pom.Developer.ID)
            email.set(Config.Pom.Developer.EMAIL)
            name.set(Config.Pom.Developer.NAME)
            organization.set(Config.Pom.Developer.ORGANIZATION)
            organizationUrl.set(Config.Pom.Developer.ORGANIZATION_URL)
        }
    }
    scm {
        connection.set("scm:git:https://github.com/casperwtf/Javet.git")
        url.set(Config.Fork.URL)
    }
}

publishing {
    repositories {
        maven {
            name = "fork"
            url = uri(layout.buildDirectory.dir(Config.Fork.REPOSITORY))
        }
    }
    publications {
        create<MavenPublication>("forkJavet") {
            groupId = Config.GROUP_ID
            artifactId = Config.Pom.ARTIFACT_ID
            version = Config.Fork.VERSION
            artifact(forkJavetJar)
            pom.forkPom(Config.NAME, Config.Pom.DESCRIPTION + " Fork with a Java 25 FFM fast path.")
        }
        create<MavenPublication>("forkV8LinuxX8664") {
            groupId = Config.GROUP_ID
            artifactId = "javet-v8-linux-x86_64"
            version = Config.Fork.VERSION
            artifact(forkV8LinuxX8664Jar)
            pom.forkPom("Javet V8 Linux x86_64", "Javet V8 native library for Linux x86_64, with the FFM entry points.")
        }
        create<MavenPublication>("generatePom") {
            from(components["java"])
            pom {
                artifactId = Config.Pom.ARTIFACT_ID
                description.set(Config.Pom.DESCRIPTION)
                groupId = Config.GROUP_ID
                name.set(Config.NAME)
                url.set(Config.URL)
                version = Config.VERSION
                licenses {
                    license {
                        name.set(Config.Pom.License.NAME)
                        url.set(Config.Pom.License.URL)
                    }
                }
                developers {
                    developer {
                        id.set(Config.Pom.Developer.ID)
                        email.set(Config.Pom.Developer.EMAIL)
                        name.set(Config.Pom.Developer.NAME)
                        organization.set(Config.Pom.Developer.ORGANIZATION)
                        organizationUrl.set(Config.Pom.Developer.ORGANIZATION_URL)
                    }
                }
                scm {
                    connection.set(Config.Pom.Scm.CONNECTION)
                    developerConnection.set(Config.Pom.Scm.DEVELOPER_CONNECTION)
                    tag.set(Config.Versions.JAVET)
                    url.set(Config.URL)
                }
                properties.set(
                    mapOf(
                        "maven.compiler.source" to Config.Versions.JAVA_VERSION,
                        "maven.compiler.target" to Config.Versions.JAVA_VERSION,
                    )
                )
            }
        }
    }
}
