import java.nio.file.Files
import java.nio.file.StandardCopyOption

/*
 * StreamEmber çekirdeği (Paper) → build/libs/streamember-core.jar
 *
 *   gradlew build     → derle
 *   gradlew deploy    → derle + Paper plugins klasörüne kopyala (MINECRAFT_SERVER_PLUGINS_PATH ya da -PpluginsDir=…)
 *
 * İnternetsiz derleme: -PpaperLibs="D:\Games\MinecaftServer\libraries"
 * Ek bağımlılık yok: HTTP/WebSocket için Java 21 HttpClient, JSON için Paper'ın getirdiği Gson.
 */
plugins { java }

description = "StreamEmber core: EventFabric, launcher and Falcon connection, module API"

val paperVersion: String by project
val paperLibs: String? = (findProperty("paperLibs") as String?)?.takeIf { it.isNotBlank() }
val pluginsDir: String? = (findProperty("pluginsDir") as String?)?.takeIf { it.isNotBlank() }
    ?: System.getenv("MINECRAFT_SERVER_PLUGINS_PATH")?.takeIf { it.isNotBlank() }

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    if (paperLibs != null) compileOnly(fileTree(paperLibs) { include("**/*.jar") })
    else compileOnly("io.papermc.paper:paper-api:$paperVersion")
}

// Paper 1.21.x Java 21 ister; derleyen JDK daha yeni olabilir.
tasks.withType<JavaCompile>().configureEach {
    options.release.set(21)
    options.encoding = "UTF-8"
    options.compilerArgs.addAll(listOf("-Xlint:deprecation", "-Xlint:unchecked"))
}

tasks.jar { archiveFileName.set("streamember-core.jar") }

tasks.processResources {
    filteringCharset = "UTF-8"
    val props = mapOf("version" to project.version.toString())
    inputs.properties(props)
    filesMatching("plugin.yml") { expand(props) }
}

tasks.register("deploy") {
    group = "streamember"
    description = "streamember-core.jar'ı derleyip Paper plugins klasörüne kopyalar."
    val source = tasks.jar.flatMap { it.archiveFile }
    inputs.file(source)
    val target = pluginsDir
    if (target != null) outputs.file(File(target, "streamember-core.jar"))
    doLast {
        if (target == null) throw GradleException(
            "Plugins klasörü bilinmiyor. MINECRAFT_SERVER_PLUGINS_PATH ortam değişkenini tanımla ya da -PpluginsDir=… ver.")
        val destination = File(target, "streamember-core.jar")
        destination.parentFile.mkdirs()
        Files.copy(source.get().asFile.toPath(), destination.toPath(), StandardCopyOption.REPLACE_EXISTING)
        logger.lifecycle("→ $destination")
        logger.lifecycle("  Çekirdek güncellendi: sunucuyu yeniden başlat (modüller çekirdeğe bağlı).")
    }
}
