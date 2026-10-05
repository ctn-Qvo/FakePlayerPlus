plugins {
    kotlin("jvm") version "2.3.0" apply false
    id("io.papermc.paperweight.userdev") version "2.0.0-beta.21" apply false
    base
}

group = "com.coderxi.plugin.fakeplayer"
version = System.getenv("APP_VERSION")?.removePrefix("v") ?: "dev"

subprojects {
    apply(plugin = "org.jetbrains.kotlin.jvm")
    group = rootProject.group
    version = rootProject.version
    repositories {
        // PaperMC 放最前,优先解析 paper 相关依赖
        maven("https://repo.papermc.io/repository/maven-public/")
        mavenCentral()
        // LuckPerms 仓库(正确地址)
        maven("https://repo.lucko.me/")
    }
    configure<org.jetbrains.kotlin.gradle.dsl.KotlinJvmProjectExtension> {
        jvmToolchain(25)
    }
}
