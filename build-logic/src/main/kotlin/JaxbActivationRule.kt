import org.gradle.api.artifacts.ComponentMetadataContext
import org.gradle.api.artifacts.ComponentMetadataRule

/**
 * japicmp resolves its own worker classpath (JAXB with javax.activation:activation 1.1.1 and, through jaxb-api,
 * javax.activation-api 1.2.0) in a detached configuration. Indra's jvm-dependency-conflict-resolution plugin gives both
 * the capability `org.gradlex:javax-activation-api`, and its conflict resolution does not reach detached
 * configurations, so the resolution fails. Removing the capability from the old module puts both jars on that
 * classpath again, as without the plugin.
 */
abstract class JaxbActivationRule : ComponentMetadataRule {
    override fun execute(context: ComponentMetadataContext) {
        context.details.allVariants {
            withCapabilities {
                removeCapability("org.gradlex", "javax-activation-api")
            }
        }
    }
}
