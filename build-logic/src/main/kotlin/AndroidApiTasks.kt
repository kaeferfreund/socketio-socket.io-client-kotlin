import kotlinx.validation.api.dump
import kotlinx.validation.api.filterOutAnnotated
import kotlinx.validation.api.filterOutNonPublic
import kotlinx.validation.api.loadApiFromJvmClasses
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * The public API of compiled classes in binary-compatibility-validator's `.api` format.
 * The validator's Gradle plugin does not see AGP 9's built-in Kotlin, so Android
 * modules run its library directly on the release classes.
 */
internal fun renderApi(classes: File): String {
    val streams = classes.walkTopDown().filter { it.isFile && it.name.endsWith(".class") }.sortedBy { it.path }.map<File, java.io.InputStream> { it.inputStream() }
    val api =
        streams
            .loadApiFromJvmClasses()
            .filterOutNonPublic(emptyList(), emptyList())
            .filterOutAnnotated(setOf("io/github/kaeferfreund/socketio/engineio/InternalSocketIOApi"))
    return api.dump(StringBuilder()).toString()
}

@CacheableTask
abstract class AndroidApiDumpTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: DirectoryProperty

    @get:OutputFile
    abstract val dumpFile: RegularFileProperty

    @TaskAction
    fun dump() {
        dumpFile.get().asFile.apply {
            parentFile.mkdirs()
            writeText(renderApi(classes.get().asFile))
        }
    }
}

abstract class AndroidApiCheckTask : DefaultTask() {
    @get:InputDirectory
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val classes: DirectoryProperty

    @get:InputFile
    @get:Optional
    @get:PathSensitive(PathSensitivity.RELATIVE)
    abstract val dumpFile: RegularFileProperty

    @TaskAction
    fun check() {
        val expected = dumpFile.orNull?.asFile?.takeIf { it.isFile }?.readText() ?: throw GradleException("Missing API dump; run apiDump")
        val actual = renderApi(classes.get().asFile)
        if (expected != actual) {
            throw GradleException("The public API changed; review the difference, run apiDump and note it in CHANGELOG.md")
        }
    }
}
