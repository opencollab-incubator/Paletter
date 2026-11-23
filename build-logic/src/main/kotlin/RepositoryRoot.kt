import org.gradle.api.tasks.Internal
import org.gradle.process.CommandLineArgumentProvider
import java.io.File

// The repository root passed to a test or a tools command. It is @Internal, so it is not part of the task's inputs:
// the absolute checkout path would otherwise make every cache key checkout dependent. What the task reads under the
// root is declared as input files, with relative path sensitivity.

/** `-Dpaletter.root=<root>`, for a test JVM (`jvmArgumentProviders`). */
class RepositoryRootProperty(@get:Internal val root: File) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("-Dpaletter.root=${root.absolutePath}")
}

/** `--root <root>`, for a tools command (`argumentProviders`). */
class RepositoryRootOption(@get:Internal val root: File) : CommandLineArgumentProvider {
    override fun asArguments(): Iterable<String> = listOf("--root", root.absolutePath)
}
