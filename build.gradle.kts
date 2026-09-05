plugins {
    java
    // ИСПОЛЬЗУЕМ НОВЫЙ АКТУАЛЬНЫЙ ПЛАГИН И ВЕРСИЮ 9.6.0
    id("com.gradleup.shadow") version "9.6.0"
}

group = "txt.console"
version = "1.1-SNAPSHOT"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // Paper API для версии 1.21 (он предоставляется сервером, поэтому compileOnly)
    compileOnly("io.papermc.paper:paper-api:1.21-R0.1-SNAPSHOT")

    // Log4j Core для создания прямого перехватчика консоли
    compileOnly("org.apache.logging.log4j:log4j-core:2.22.1")

    // Javalin для веб-сервера и WebSocket (implementation - будет вшито в плагин)
    implementation("io.javalin:javalin:6.1.3")
    implementation("com.fasterxml.jackson.core:jackson-databind:2.17.1") // Для работы с JSON
}

val targetJavaVersion = 21
java {
    val javaVersion = JavaVersion.toVersion(targetJavaVersion)
    sourceCompatibility = javaVersion
    targetCompatibility = javaVersion
    if (JavaVersion.current() < javaVersion) {
        toolchain.languageVersion = JavaLanguageVersion.of(targetJavaVersion)
    }
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
    if (targetJavaVersion >= 10 || JavaVersion.current().isJava10Compatible) {
        options.release.set(targetJavaVersion)
    }
}

// Настройка ShadowJar: единственный итоговый jar без приписки -all
tasks.shadowJar {
    archiveClassifier.set("")
    relocate("io.javalin", "txt.console.webconsole.libs.javalin")
    relocate("com.fasterxml.jackson", "txt.console.webconsole.libs.jackson")
}

// Тонкий jar не нужен - чтобы не создавалось два jar с одинаковым именем, отключаем его
tasks.jar {
    enabled = false
}

// Заменяем стандартный build на shadowJar
tasks.build {
    dependsOn("shadowJar")
}