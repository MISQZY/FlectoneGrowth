plugins {
    java
    // com.github.johnrengelman.shadow is unmaintained (its ASM can't read
    // Java 25 class files - "Unsupported class file major version 69").
    // com.gradleup.shadow is the actively maintained fork, same task API.
    // The project now runs on Gradle 9 (needed for the Java-25 toolchain -
    // see gradle.properties/javaVersion), so use the shadow 9.x line built
    // for Gradle 9, which bundles a current-enough ASM natively. (An
    // earlier attempt forced ASM onto shadow 8.3.6 via a `buildscript`
    // classpath override - that read Java 25 class files fine, but its
    // Groovy relocation code called an ASM `Remapper` method whose
    // signature had changed in the forced-newer ASM, throwing
    // `MissingMethodException`. Not worth chasing further once a shadow
    // version built for this ASM/Gradle combination was available.)
    id("com.gradleup.shadow") version "9.6.1" apply false
}

allprojects {
    group = "org.misqzy.flectonegrowth"
    version = providers.gradleProperty("pluginVersion").get()

    repositories {
        mavenCentral()
        mavenLocal()
    }
}

subprojects {
    apply(plugin = "java")

    java {
        toolchain.languageVersion.set(JavaLanguageVersion.of(providers.gradleProperty("javaVersion").get().toInt()))
    }

    // Centralized here rather than per-module: every module with hand-written
    // Java source (core, minecraft/bukkit, minecraft/velocity) benefits from
    // the same boilerplate reduction, and a single version avoids the three
    // modules drifting out of sync with each other.
    dependencies {
        "compileOnly"("org.projectlombok:lombok:${rootProject.findProperty("lombokVersion")}")
        "annotationProcessor"("org.projectlombok:lombok:${rootProject.findProperty("lombokVersion")}")
    }

    tasks.withType<JavaCompile> {
        options.encoding = "UTF-8"
        options.release.set(providers.gradleProperty("javaVersion").get().toInt())
    }

    tasks.withType<ProcessResources> {
        filteringCharset = "UTF-8"
    }

    // Single source of truth for the plugin version is the `pluginVersion`
    // gradle property (see `allprojects` above). plugin.yml picks it up via
    // ProcessResources token expansion, but that mechanism only reaches
    // `src/main/resources` - some modules also need the version as a real
    // Java compile-time constant (Velocity's `@Plugin(version = ...)`
    // annotation requires one; annotations can't take arbitrary runtime
    // expressions). This generates a tiny `BuildVersion` class per module
    // that needs it, from the same property, so there is exactly one place
    // (`gradle.properties`) anyone ever edits the version.
    val buildVersionPackages = mapOf(
        "bukkit" to "org.misqzy.flectonegrowth.bukkit",
        "velocity" to "org.misqzy.flectonegrowth.velocity",
    )
    buildVersionPackages[project.name]?.let { packageName ->
        val outputDir = layout.buildDirectory.dir("generated/sources/buildVersion/java/main")
        val generateBuildVersion = tasks.register("generateBuildVersion") {
            val version = project.version.toString()
            inputs.property("version", version)
            outputs.dir(outputDir)
            doLast {
                val packageDir = outputDir.get().dir(packageName.replace('.', '/')).asFile
                packageDir.mkdirs()
                packageDir.resolve("BuildVersion.java").writeText(
                    """
                    package $packageName;

                    /** Generated from the {@code pluginVersion} gradle property - do not edit by hand. */
                    public final class BuildVersion {
                        public static final String VERSION = "$version";
                        private BuildVersion() {}
                    }
                    """.trimIndent()
                )
            }
        }
        extensions.getByType<JavaPluginExtension>().sourceSets["main"].java.srcDir(generateBuildVersion)
    }

    // Gradle's default jar/shadowJar output name is the project's own
    // directory name - with the core/minecraft/{platform} layout that's
    // just "bukkit.jar", "velocity.jar", etc, which is far too generic for a
    // jar someone drops into a plugins/ folder alongside a dozen others.
    // Reuses buildVersionPackages' keys since it's the same "which projects
    // are actual distributable platform plugins" set.
    if (buildVersionPackages.containsKey(project.name)) {
        extensions.configure<BasePluginExtension> {
            archivesName.set("FlectoneGrowth-${project.name.replaceFirstChar(Char::uppercase)}")
        }
    }
}
