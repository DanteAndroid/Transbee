package transbee.tasks

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.util.Properties

abstract class PrepareFeishuKeyPropertiesTask : DefaultTask() {

    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val sourceDirectory: DirectoryProperty

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    init {
        outputs.upToDateWhen { false }
    }

    @TaskAction
    fun prepare() {
        val sourceDir = sourceDirectory.get().asFile
        val outputDir = outputDirectory.get().asFile
        outputDir.deleteRecursively()
        outputDir.mkdirs()
        sourceDir.copyRecursively(outputDir, overwrite = true)

        val envAppId = System.getenv("FEISHU_APP_ID")?.trim().orEmpty()
        val envAppSecret = System.getenv("FEISHU_APP_SECRET")?.trim().orEmpty()
        if (envAppId.isEmpty() && envAppSecret.isEmpty()) return

        val missing = buildList {
            if (envAppId.isEmpty()) add("FEISHU_APP_ID")
            if (envAppSecret.isEmpty()) add("FEISHU_APP_SECRET")
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "飞书构建凭据缺少或为空: ${missing.joinToString()}。" +
                    "请同时设置 FEISHU_APP_ID 和 FEISHU_APP_SECRET。",
            )
        }

        val properties = Properties().apply {
            setProperty("appId", envAppId)
            setProperty("appSecret", envAppSecret)
        }
        outputDir.resolve("key.properties").writer(Charsets.UTF_8).use {
            properties.store(it, null)
        }
    }
}

abstract class ValidateFeishuReleaseCredentialsTask : DefaultTask() {

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.NONE)
    abstract val keyPropertiesFile: RegularFileProperty

    @TaskAction
    fun validateCredentials() {
        val keyFile = keyPropertiesFile.get().asFile
        if (!keyFile.isFile) {
            throw GradleException(
                "发布打包缺少飞书凭据。请设置 FEISHU_APP_ID 和 FEISHU_APP_SECRET；" +
                    "GitHub Actions 请配置同名 Repository Secrets。" +
                    "本地也可使用 composeApp/src/jvmMain/resources/key.properties。",
            )
        }
        val properties = Properties().apply {
            keyFile.reader(Charsets.UTF_8).use { load(it) }
        }
        val missing = listOf("appId", "appSecret").filter {
            properties.getProperty(it).isNullOrBlank()
        }
        if (missing.isNotEmpty()) {
            throw GradleException(
                "发布打包的 key.properties 缺少或为空: ${missing.joinToString()}。" +
                    "请检查 FEISHU_APP_ID、FEISHU_APP_SECRET 或本地 key.properties。",
            )
        }
    }
}
