# casperwtf/Javet Maven repository

Maven layout for the [java25-ffm](https://github.com/casperwtf/Javet/tree/java25-ffm) fork of Javet: one
multi-release jar that keeps Javet's Java 8 baseline and, on Java 25+, calls V8 through the Foreign Function and
Memory API instead of JNI.

Repository URL: `https://raw.githubusercontent.com/casperwtf/Javet/maven`

| Artifact | Version |
|---|---|
| `com.caoccao.javet:javet` | `6.0.1-ffm.1` |
| `com.caoccao.javet:javet-v8-linux-x86_64` | `6.0.1-ffm.1` |

```kotlin
repositories {
    maven("https://raw.githubusercontent.com/casperwtf/Javet/maven")
}
dependencies {
    implementation("com.caoccao.javet:javet:6.0.1-ffm.1")
    implementation("com.caoccao.javet:javet-v8-linux-x86_64:6.0.1-ffm.1")
}
```

Run with `--enable-native-access=ALL-UNNAMED`. `-Djavet.ffm=false` stays on JNI. Other platforms' native jars from
Maven Central work with this `javet` jar and fall back to JNI.

Regenerate from `java25-ffm`: build the native library, run
`./gradlew publishForkJavetPublicationToForkRepository publishForkV8LinuxX8664PublicationToForkRepository`
and copy `build/maven-repo/com` here.
