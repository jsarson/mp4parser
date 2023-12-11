plugins {
    application
}

group = "org.example"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":mp4parser:isoparser"))

    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-android:1.7.30")

    implementation("org.eclipse.jetty:jetty-server:8.1.7.v20120910")
    implementation("org.aspectj:aspectjtools:1.9.7")

    implementation("commons-io:commons-io:2.5")
    implementation("commons-codec:commons-codec:1.10")
    implementation("commons-lang:commons-lang:2.6")
}

tasks.test {
    useJUnitPlatform()
}