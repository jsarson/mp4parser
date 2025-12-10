plugins {
    // This module is packaged as a plain Java library; no application entry point.
    `java-library`
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

group = "org.example"
version = "1.0-SNAPSHOT"

dependencies {
    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-android:1.7.30")

    // Only the runtime annotations are needed; the tooling (aspectjtools) pulls in Swing/AWT classes
    // that are not available on Android and make R8 complain about missing java.desktop classes.
    implementation("org.aspectj:aspectjrt:1.9.7")

    implementation("commons-io:commons-io:2.5")
    implementation("commons-codec:commons-codec:1.16.0")
    implementation("commons-lang:commons-lang:2.6")
    testImplementation("junit:junit:4.12")
}

tasks.test {
    useJUnitPlatform()
}
