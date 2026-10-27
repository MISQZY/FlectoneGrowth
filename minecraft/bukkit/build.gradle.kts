plugins {
    id("com.gradleup.shadow")
}


repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
    // The old /content/repositories/placeholders/ path 404s now; ExtendedClip
    // moved their release repo to /releases/ (verified live, see docs/BUILD.md).
    maven("https://repo.extendedclip.com/releases/")
}

dependencies {
    implementation(project(":core"))

    // spigot-api (not paper-api) so this module only ever compiles against
    // what plain Bukkit/CraftBukkit/Spigot actually provide - this is the
    // single distributable module, installed unmodified on Spigot,
    // CraftBukkit, Paper, and forks like Purpur. Where Paper genuinely
    // offers something better (see LegacyText's Audience capability check),
    // it's detected at runtime instead of adding a paper-api dependency.
    compileOnly("org.spigotmc:spigot-api:${project.findProperty("spigotVersion")}")
    compileOnly("me.clip:placeholderapi:${project.findProperty("placeholderapiVersion")}")
    // FlectonePulse's stable public API (Maven Central). compileOnly: the
    // FlectonePulse plugin provides it at runtime (softdepend), and shadowJar
    // below additionally excludes net/flectone/** as a safeguard.
    compileOnly("net.flectone.pulse:api:${project.findProperty("flectonePulseVersion")}")
    // Unlike paper-api, spigot-api's own pom does not pull in com.mojang:brigadier
    // transitively (verified: no such dependency in its published pom.xml) - but
    // cloud-bukkit transitively depends on cloud-brigadier, whose compiled classes
    // reference com.mojang.brigadier.* types, so javac needs them resolvable even
    // though this module never calls BukkitCommandManager#registerBrigadier(). The
    // actual server jar always bundles brigadier at runtime (vanilla Minecraft uses
    // it internally), so this is compileOnly, matching spigot-api itself above.
    compileOnly("com.mojang:brigadier:1.0.500")

    implementation("org.incendo:cloud-core:${project.findProperty("cloudCoreVersion")}")
    implementation("org.incendo:cloud-bukkit:${project.findProperty("cloudBukkitVersion")}")
    implementation("org.incendo:cloud-annotations:${project.findProperty("cloudCoreVersion")}")

    // Vanilla Spigot/CraftBukkit does not bundle kyori Adventure, and its
    // CommandSender doesn't implement Audience the way Paper's does - see
    // LegacyText's runtime capability check, which uses native Adventure
    // delivery when available and hand-converts to a BungeeCord
    // BaseComponent tree otherwise (bungeecord-chat comes transitively via
    // spigot-api above, no separate dependency needed). Not
    // net.kyori:adventure-platform-bukkit or adventure-text-serializer-
    // bungeecord as a ready-made bridge for that fallback: both are part of
    // the same adventure-platform release train, still pinned to
    // adventure-api 4.21.0 with no 5.x-compatible release as of writing
    // (verified live against their published pom.xml - adventure-text-
    // serializer-bungeecord's own transitive graph even needs an
    // adventure-text-serializer-*-legacy-impl module that plainly has no
    // 5.x release at all) - conflicts with the 5.2.0 this project already
    // depends on via core, the same reasoning LegacyText's own class
    // javadoc documents for why it hand-rolls this conversion instead.
    implementation("net.kyori:adventure-api:${project.findProperty("adventureVersion")}")
    implementation("net.kyori:adventure-text-minimessage:${project.findProperty("adventureVersion")}")

    implementation("com.google.inject:guice:${project.findProperty("guiceVersion")}")
    implementation("org.yaml:snakeyaml:${project.findProperty("snakeyamlVersion")}")
    implementation("com.zaxxer:HikariCP:${project.findProperty("hikariVersion")}")
    // HikariCP (and the MySQL/MariaDB drivers, if they detect it) log through slf4j-api. Without a
    // provider slf4j prints a "No SLF4J providers were found" banner to stderr and Hikari's pool
    // start/stop chatter would go wherever the server's slf4j points. Bundle the no-op binding (same
    // 1.7.x line as the slf4j-api Hikari 6.x pulls in) and relocate both, so this stays fully private.
    // Real storage failures are reported by PluginLog, not by Hikari.
    implementation("org.slf4j:slf4j-nop:1.7.36")
    implementation("com.h2database:h2:${project.findProperty("h2Version")}")
    implementation("com.mysql:mysql-connector-j:${project.findProperty("mysqlVersion")}")
    implementation("org.mariadb.jdbc:mariadb-java-client:${project.findProperty("mariadbVersion")}")
}

tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar") {
    // This is now an internal intermediate, not the final artifact (see
    // pluginJar below) - written to a separate directory (not the default
    // build/libs/) so only pluginJar's own output ever shows up there,
    // rather than sitting right next to it under a distinguishing classifier
    // that's still easy to mistake for a second real build artifact.
    archiveClassifier.set("premerge")
    destinationDirectory.set(layout.buildDirectory.dir("intermediates/shadow"))
    relocate("com.google.inject", "org.misqzy.flectonegrowth.bukkit.libs.guice")
    relocate("com.zaxxer.hikari", "org.misqzy.flectonegrowth.bukkit.libs.hikari")
    relocate("org.slf4j", "org.misqzy.flectonegrowth.bukkit.libs.slf4j")
    relocate("org.yaml.snakeyaml", "org.misqzy.flectonegrowth.bukkit.libs.snakeyaml")
    relocate("org.incendo.cloud", "org.misqzy.flectonegrowth.bukkit.libs.cloud")
    relocate("net.kyori", "org.misqzy.flectonegrowth.bukkit.libs.kyori")
    // Never bundle (or relocate) FlectonePulse's API - the real plugin supplies it.
    exclude("net/flectone/**")
    //
    // minimize() is deliberately NOT used: it depends on org.vafer:jdependency,
    // which bundles an ASM version that cannot parse Java 25 class files
    // ("Unsupported class file major version 69") - a real failure hit
    // against this project's Java-25 toolchain, not a hypothetical. It only
    // trims shaded-jar size, not correctness, so it's dropped until that
    // tooling catches up.
}

// The plain `jar` task's own output would otherwise collide with pluginJar's
// (below) path - it never produced anything meaningful on its own anyway
// (shadowJar/pluginJar have always been the real output), so disabling it
// outright is simpler than juggling classifiers three ways.
tasks.named("jar") {
    enabled = false
}

/**
 * Zip-merges this module's own shadowJar (already fully relocated, above)
 * with minecraft:velocity's plain jar output, as
 * `FlectoneGrowth-Bukkit-<version>.jar` - the one file this build actually
 * produces for anyone to install; minecraft:velocity no longer has a
 * distributable jar of its own to grab separately (its `jar` task output is
 * now purely an internal input to this one, consumed straight from its own
 * module's build/libs/). Works dropped into either a Bukkit-family server's
 * plugins/ folder (reads plugin.yml, loads FlectoneGrowthPlugin) or a
 * Velocity proxy's (reads velocity-plugin.json, loads
 * FlectoneGrowthVelocityPlugin) - see docs/ARCHITECTURE.md.
 *
 * Deliberately NOT done by adding minecraft:velocity as a compileOnly/
 * implementation dependency of this module and letting shadowJar merge it
 * directly: that route was tried and reverted after decompiling the actual
 * merged output with javap - shadowJar's relocators rewrite EVERY class's
 * bytecode uniformly, including velocity's. FlectoneGrowthVelocityPlugin's
 * constructor is annotated @com.google.inject.Inject (via Lombok's
 * onConstructor_) for Velocity's own injector to find via reflection at
 * plugin-load time; the guice relocation above rewrote that to
 * @org.misqzy.flectonegrowth.bukkit.libs.guice.Inject in the merged class
 * file, which Velocity's own (unrelocated) Guice on the proxy's classpath
 * would never recognize as an injection point - a real, verified breakage,
 * not a hypothetical. Shadow's per-relocator `exclude(...)` doesn't help
 * either: it's matched against the class being relocated *to* (i.e. paths
 * under com.google.inject.**), not which class is doing the referencing, so
 * excluding org.misqzy.flectonegrowth.velocity.** there is simply a no-op
 * (confirmed by testing it before writing this version).
 *
 * This merge-after-the-fact approach sidesteps the problem entirely: nothing
 * from minecraft:velocity's jar ever passes through this module's relocators
 * at all, so FlectoneGrowthVelocityPlugin's bytecode stays byte-for-byte
 * identical to what minecraft:velocity's own `jar` task already produces
 * (Guice's own Inject annotation class itself isn't bundled here either way -
 * Velocity supplies it externally from the proxy's own classpath, same as
 * always; only the *reference* to it needed to stay untouched).
 */
val pluginJar = tasks.register<Jar>("pluginJar") {
    dependsOn(tasks.named("shadowJar"), project(":minecraft:velocity").tasks.named("jar"))
    group = "build"
    description = "Merges the Bukkit shadowJar and the Velocity jar into the one FlectoneGrowth-Bukkit-<version>.jar this build installs."
    archiveBaseName.set("FlectoneGrowth-Bukkit")
    archiveClassifier.set("")
    archiveVersion.set(project.version.toString())
    destinationDirectory.set(layout.buildDirectory.dir("libs"))
    duplicatesStrategy = DuplicatesStrategy.EXCLUDE

    from({ zipTree(tasks.named<com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar>("shadowJar").get().archiveFile) })
    from({ zipTree((project(":minecraft:velocity").tasks.named("jar").get() as Jar).archiveFile) })
}

tasks.build {
    dependsOn(pluginJar)
}

tasks.named<ProcessResources>("processResources") {
    val props = mapOf("pluginVersion" to project.version.toString())
    inputs.properties(props)
    filesMatching(listOf("plugin.yml", "config.yml")) {
        expand(props)
    }
}
