import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult

/** Every external module of a resolved graph, transitive ones included, as `group:module:version` (sorted). */
fun resolvedModules(root: ResolvedComponentResult): List<String> {
    val seen = mutableSetOf(root)
    val queue = ArrayDeque(listOf(root))
    val modules = sortedSetOf<String>()
    while (queue.isNotEmpty()) {
        val component = queue.removeFirst()
        val id = component.id
        if (id is ModuleComponentIdentifier) {
            // The selected version as requested: a pinned snapshot keeps its timestamp, a floating one ends in -SNAPSHOT
            modules.add("${id.group}:${id.module}:${component.moduleVersion?.version ?: id.version}")
        }
        for (dependency in component.dependencies) {
            if (dependency is ResolvedDependencyResult && seen.add(dependency.selected)) {
                queue.add(dependency.selected)
            }
        }
    }
    return modules.toList()
}

/** A snapshot named by its upload timestamp (`3.0.0.Beta15-20261116.161745-6`): one build, but still a snapshot. */
private val TIMESTAMPED_SNAPSHOT = Regex("""-\d{8}\.\d{6}-\d+$""")

fun isFloatingSnapshot(module: String) = module.endsWith("-SNAPSHOT")

fun isSnapshot(module: String) = isFloatingSnapshot(module) || TIMESTAMPED_SNAPSHOT.containsMatchIn(module)
