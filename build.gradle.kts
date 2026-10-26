@file:OptIn(ExperimentalKotlinGradlePluginApi::class, ExperimentalAbiValidation::class)

import org.jetbrains.kotlin.gradle.ExperimentalKotlinGradlePluginApi
import org.jetbrains.kotlin.gradle.dsl.JvmDefaultMode
import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.KotlinVersion
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation
import org.jetbrains.kotlin.gradle.tasks.KotlinCompile
import org.jreleaser.model.Active

plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.plugin.power.assert)
    alias(libs.plugins.dokka)
    alias(libs.plugins.version.catalog.update)
    alias(libs.plugins.maven.publish)
    alias(libs.plugins.jreleaser)
    alias(libs.plugins.xemantic.conventions)
}

group = "com.xemantic.kotlin"

xemantic {
    description = "Kotlin API introspection of the classpath, rendering LLM-friendly signatures"
    inceptionYear = "2026"
    applyAllConventions()
}

fun MavenPomDeveloperSpec.projectDevs() {
    developer {
        id = "morisil"
        name = "Kazik Pogoda"
        url = "https://github.com/morisil"
    }
}

val javaTarget = libs.versions.javaTarget.get()
val kotlinTarget = KotlinVersion.fromVersion(libs.versions.kotlinTarget.get())

kotlin {

    explicitApi()

    abiValidation {}

    // set up according to https://jakewharton.com/gradle-toolchains-are-rarely-a-good-idea/
    compilerOptions {
        apiVersion = kotlinTarget
        languageVersion = kotlinTarget
        jvmTarget = JvmTarget.fromTarget(javaTarget)
        freeCompilerArgs.addAll(
            "-Xjdk-release=$javaTarget",
            "-Xcontext-sensitive-resolution"
        )
        extraWarnings = true
        progressiveMode = true
    }

}

java {
    sourceCompatibility = JavaVersion.toVersion(javaTarget)
    targetCompatibility = JavaVersion.toVersion(javaTarget)
}

repositories {
    mavenCentral()
}

// compiled classes deliberately kept off the test classpath,
// so that tests can expose them through a dedicated class loader only
val isolatedTestFixtures: SourceSet = sourceSets.create("isolatedTestFixtures")

// compiled with the bodies of interface members relocated to DefaultImpls, like the libraries targeting old JVMs
val jvmDefaultDisabledTestFixtures: SourceSet = sourceSets.create("jvmDefaultDisabledTestFixtures")

tasks.named<KotlinCompile>("compileJvmDefaultDisabledTestFixturesKotlin") {
    compilerOptions.jvmDefault = JvmDefaultMode.DISABLE
}

// compiled for Java 8, like the libraries still targeting it, without the NestMembers attribute of Java 11;
// once a JDK running the build drops --release 8, this compilation fails, instead of silently changing the fixtures,
// and the attribute can be stripped from the class files compiled for a later release instead, like ClassFileRewriting does
val java8TestFixtures: SourceSet = sourceSets.create("java8TestFixtures")

tasks.named<JavaCompile>(java8TestFixtures.compileJavaTaskName) {
    options.release = 8
    // not warning that the release 8 is obsolete
    options.compilerArgs.add("-Xlint:-options")
}

// a separate Kotlin module, whose internal members are not visible to the test module
val otherModuleTestFixtures: SourceSet = sourceSets.create("otherModuleTestFixtures")

// a friend module of otherModuleTestFixtures, like a test module of the main one, seeing its internal members
val friendModuleTestFixtures: SourceSet = sourceSets.create("friendModuleTestFixtures")

kotlin.target.compilations.named(friendModuleTestFixtures.name) {
    associateWith(kotlin.target.compilations.getByName(otherModuleTestFixtures.name))
}

// two unrelated Kotlin modules, both with the default module name main, which the compiler omits from the metadata,
// like when compiled with plain kotlinc, without -module-name;
// both write META-INF/main.kotlin_module, so that neither may declare top-level members, which a test guards
val defaultModuleTestFixtures: SourceSet = sourceSets.create("defaultModuleTestFixtures")
val otherDefaultModuleTestFixtures: SourceSet = sourceSets.create("otherDefaultModuleTestFixtures")

listOf(defaultModuleTestFixtures, otherDefaultModuleTestFixtures).forEach { sourceSet ->
    tasks.named<KotlinCompile>(sourceSet.getCompileTaskName("kotlin")) {
        compilerOptions.moduleName = "main"
    }
}

dependencies {
    "otherDefaultModuleTestFixturesImplementation"(defaultModuleTestFixtures.output)

    implementation(libs.kotlin.metadata.jvm)
    implementation(libs.kotlin.reflect)
    // reading the @Metadata of the scanned classes without loading them
    implementation(libs.asm)
    implementation(libs.xemantic.kotlin.core)
    implementation(libs.kotlin.logging)
    // kotlin-logging 8 no longer brings SLF4J transitively
    implementation(libs.slf4j.api)

    testImplementation(jvmDefaultDisabledTestFixtures.output)
    testImplementation(otherModuleTestFixtures.output)
    testImplementation(friendModuleTestFixtures.output)
    testImplementation(defaultModuleTestFixtures.output)
    testImplementation(otherDefaultModuleTestFixtures.output)
    testImplementation(libs.kotlin.test)
    testImplementation(libs.xemantic.kotlin.test)
    // GeneratedSerializer, implemented by the fixtures of the serializers generated by kotlinx.serialization
    testImplementation(libs.kotlinx.serialization.core)
    testRuntimeOnly(libs.logback.classic)
}

configurations.all {
    resolutionStrategy.eachDependency {
        if (requested.group == "org.ow2.asm") {
            useVersion(libs.versions.asm.get())
        }
    }
}

tasks.test {
    useJUnitPlatform()
    val isolatedTestFixturesDirs = isolatedTestFixtures.output.classesDirs
    inputs.files(isolatedTestFixturesDirs).withPropertyName("isolatedTestFixtures")
    systemProperty("isolatedTestFixtures", isolatedTestFixturesDirs.asPath)
    val java8TestFixturesDirs = java8TestFixtures.output.classesDirs
    inputs.files(java8TestFixturesDirs).withPropertyName("java8TestFixtures")
    systemProperty("java8TestFixtures", java8TestFixturesDirs.asPath)
}

// the rendered declarations of the whole test classpath parsed by the Kotlin compiler,
// a separate suite, not to put the compiler on the classpath the other tests introspect
testing {
    suites {
        register<JvmTestSuite>("parseTest") {
            dependencies {
                implementation(project())
                implementation(libs.kotlin.test.junit5)
                implementation(libs.xemantic.kotlin.test)
                implementation(libs.kotlin.compiler.embeddable)
                // reading the names of the top-level declarations of the file facades
                implementation(libs.kotlin.metadata.jvm)
                runtimeOnly(libs.logback.classic)
            }
            targets.configureEach {
                testTask.configure {
                    // the legacy context receivers added to their metadata, like by the tests, see ClassFileRewriting
                    val isolatedTestFixturesDirs = isolatedTestFixtures.output.classesDirs
                    inputs.files(isolatedTestFixturesDirs).withPropertyName("isolatedTestFixtures")
                    systemProperty("isolatedTestFixtures", isolatedTestFixturesDirs.asPath)
                    val introspectedClasspath = sourceSets.test.get().runtimeClasspath
                    inputs.files(introspectedClasspath)
                        .withPropertyName("introspectedClasspath")
                        .withNormalizer(ClasspathNormalizer::class)
                    jvmArgumentProviders.add(CommandLineArgumentProvider {
                        listOf("-DintrospectedClasspath=${introspectedClasspath.asPath}")
                    })
                }
            }
        }
    }
}

// reading the names of the built-in types, which the search does not find,
// and rewriting the class files of the fixtures, like the tests do, see ClassFileRewriting
kotlin.target.compilations.matching { it.name == "parseTest" }.configureEach {
    associateWith(kotlin.target.compilations.getByName("main"))
    associateWith(kotlin.target.compilations.getByName("test"))
}

tasks.check {
    dependsOn(testing.suites.named("parseTest"))
}

powerAssert {
    functions = listOf(
        "com.xemantic.kotlin.test.assert",
        "com.xemantic.kotlin.test.have"
    )
    // only the test source set is included by default
    includedSourceSets = listOf("test", "parseTest")
}

// https://kotlinlang.org/docs/dokka-migration.html#adjust-configuration-options
dokka {
    pluginsConfiguration.html {
        footerMessage = xemantic.copyright
    }
}

versionCatalogUpdate {
    // preserve the manual, logically-grouped ordering of libs.versions.toml
    sortByKey = false
    keep {
        // kotlinTarget / javaTarget are plain version constants with no version.ref
        versions = setOf("kotlinTarget", "javaTarget")
        keepUnusedVersions = false
    }
}

mavenPublishing {

    signAllPublications()

    publishToMavenCentral(automaticRelease = true)

    pom {

        name = rootProject.name
        description = xemantic.description
        inceptionYear = xemantic.inceptionYear
        url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}"

        organization {
            name = xemantic.organization
            url = xemantic.organizationUrl
        }

        licenses {
            license {
                name = "GNU Affero General Public License v3.0"
                url = "https://www.gnu.org/licenses/agpl-3.0.txt"
                distribution = "https://www.gnu.org/licenses/agpl-3.0.txt"
            }
        }

        scm {
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}"
            connection = "scm:git:git://github.com/${xemantic.gitHubAccount}/${rootProject.name}.git"
            developerConnection = "scm:git:ssh://git@github.com/${xemantic.gitHubAccount}/${rootProject.name}.git"
        }

        ciManagement {
            system = "GitHub"
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}/actions"
        }

        issueManagement {
            system = "GitHub"
            url = "https://github.com/${xemantic.gitHubAccount}/${rootProject.name}/issues"
        }

        developers {
            projectDevs()
        }

    }

}

val releaseAnnouncementSubject = """🚀 ${rootProject.name} $version has been released!"""
val releaseAnnouncement = """
$releaseAnnouncementSubject

${xemantic.description}

${xemantic.releasePageUrl}
""".trim()

jreleaser {

    announce {
        webhooks {
            create("discord") {
                active = Active.ALWAYS
                message = releaseAnnouncement
                messageProperty = "content"
                structuredMessage = true
            }
        }
        linkedin {
            active = Active.ALWAYS
            subject = releaseAnnouncementSubject
            message = releaseAnnouncement
        }
        bluesky {
            active = Active.ALWAYS
            status = releaseAnnouncement
        }
    }

}
