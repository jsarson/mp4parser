plugins {
    application
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

group = "org.example"
version = "1.0-SNAPSHOT"

dependencies {
    implementation(project(":mp4parser:isoparser"))

    implementation("org.slf4j:slf4j-api:1.7.36")
    implementation("org.slf4j:slf4j-android:1.7.30")

    implementation("org.aspectj:aspectjtools:1.9.7")

    implementation("commons-io:commons-io:2.5")
    implementation("commons-codec:commons-codec:1.10")
    implementation("commons-lang:commons-lang:2.6")

    testImplementation("org.junit.jupiter:junit-jupiter-api:5.8.1")
}

tasks.test {
    useJUnitPlatform()
}