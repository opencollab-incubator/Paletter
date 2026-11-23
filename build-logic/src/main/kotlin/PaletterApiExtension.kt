import org.gradle.api.provider.ListProperty

/** The `paletterApi` block of paletter.api-conventions. */
interface PaletterApiExtension {
    /** The dependency jars module-info.java requires, by file name prefix: they go on compileModuleInfo's module path. */
    val modulePathPrefixes: ListProperty<String>
}
