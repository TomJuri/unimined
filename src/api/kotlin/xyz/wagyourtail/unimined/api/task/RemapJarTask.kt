package xyz.wagyourtail.unimined.api.task

import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.jvm.tasks.Jar
import org.jetbrains.annotations.ApiStatus
import xyz.wagyourtail.unimined.api.mapping.MappingNamespace
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig

/**
 * task responsible for transforming your built jar to production.
 * @since 0.1.0
 */
@Suppress("LeakingThis")
abstract class RemapJarTask : Jar() {

    @get:InputFile
    abstract val inputFile: RegularFileProperty

    /**
     * the dev env mappings
     */
    @get:Input
    @get:Optional
    @get:ApiStatus.Internal
    abstract val sourceNamespace: Property<MappingNamespace?>

    /**
     * the dev env fallback mappings
     * defaults to {@link mcConfig.mappings.devNamespace}
     */
    @get:Input
    @get:Optional
    @get:ApiStatus.Internal
    abstract val fallbackFromNamespace: Property<MappingNamespace?>

    /**
     * the prod env mappings
     * defaults to {@link mcConfig.mcProvider.prodNamespace}
     */
    @get:Input
    @get:Optional
    @get:ApiStatus.Internal
    abstract val targetNamespace: Property<MappingNamespace?>

    /**
     * whether to remap AccessTransformers to the legacy format (<=1.7.10)
     */
    @get:Input
    @get:Optional
    abstract val remapATToLegacy: Property<Boolean?>

    /**
     * env type to remap against
     */
    @get:Input
    @get:Optional
    @get:ApiStatus.Internal
    abstract val envType: Property<EnvType?>

    fun setSource(namespace: String) {
        sourceNamespace.set(MappingNamespace.getNamespace(namespace))
    }

    fun setFallbackSource(namespace: String) {
        fallbackFromNamespace.set(MappingNamespace.getNamespace(namespace))
    }

    fun setTarget(namespace: String) {
        targetNamespace.set(MappingNamespace.getNamespace(namespace))
    }

    /**
     * env type to remap against
     */
    fun setEnv(envType: String) {
        this.envType.set(EnvType.valueOf(envType))
    }

    init {
        sourceNamespace.convention(null as MappingNamespace?)
        fallbackFromNamespace.convention(null as MappingNamespace?)
        targetNamespace.convention(null as MappingNamespace?)
        remapATToLegacy.convention(null as Boolean?)
        envType.convention(null as EnvType?)
    }

}