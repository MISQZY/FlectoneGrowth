plugins {
    id("java-library")
}

dependencies {
    api("com.google.inject:guice:${project.findProperty("guiceVersion")}") { exclude(group = "com.google.guava", module = "guava") }
    api("com.google.guava:guava:33.2.1-jre")
    api("net.kyori:adventure-api:${project.findProperty("adventureVersion")}")
    api("net.kyori:adventure-text-minimessage:${project.findProperty("adventureVersion")}")
    api("org.yaml:snakeyaml:${project.findProperty("snakeyamlVersion")}")

    implementation("com.zaxxer:HikariCP:${project.findProperty("hikariVersion")}")
    compileOnly("com.h2database:h2:${project.findProperty("h2Version")}")
    compileOnly("com.mysql:mysql-connector-j:${project.findProperty("mysqlVersion")}")
    compileOnly("org.mariadb.jdbc:mariadb-java-client:${project.findProperty("mariadbVersion")}")

    compileOnly("org.jetbrains:annotations:24.1.0")

    testImplementation(platform("org.junit:junit-bom:${project.findProperty("junitVersion")}"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    // H2 is compileOnly above (the platform plugin supplies it at runtime); storage tests need it for real.
    testRuntimeOnly("com.h2database:h2:${project.findProperty("h2Version")}")
}

tasks.test {
    useJUnitPlatform()
    // Message-catalog tests read the bundled localizations straight from the bukkit module.
    systemProperty("localizations.dir", rootProject.file("minecraft/bukkit/src/main/resources/localizations").absolutePath)
}

// core is a plain library jar consumed by every platform module - it is
// never shaded/published on its own, so no shadow plugin is applied here.
