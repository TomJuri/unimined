package xyz.wagyourtail.unimined.internal.minecraft.task

import net.fabricmc.loom.util.kotlin.KotlinClasspathService
import net.fabricmc.loom.util.kotlin.KotlinRemapperClassloader
import net.fabricmc.tinyremapper.OutputConsumerPath
import net.fabricmc.tinyremapper.TinyRemapper
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.TaskAction
import xyz.wagyourtail.unimined.api.mapping.MappingNamespaceTree
import xyz.wagyourtail.unimined.api.mapping.mixin.MixinRemapOptions
import xyz.wagyourtail.unimined.api.minecraft.MinecraftConfig
import xyz.wagyourtail.unimined.api.minecraft.patch.forge.ForgeLikePatcher
import xyz.wagyourtail.unimined.api.minecraft.task.RemapJarTask
import xyz.wagyourtail.unimined.internal.mapping.at.AccessTransformerApplier
import xyz.wagyourtail.unimined.internal.mapping.aw.AccessWidenerApplier
import xyz.wagyourtail.unimined.internal.mapping.extension.MixinRemapExtension
import xyz.wagyourtail.unimined.util.*
import java.nio.file.Path
import java.nio.file.StandardOpenOption
import javax.inject.Inject
import kotlin.io.path.*

@Suppress("UNCHECKED_CAST")
abstract class RemapJarTaskImpl @Inject constructor(@get:Internal val provider: MinecraftConfig): RemapJarTask() {

    private var mixinRemapOptions: MixinRemapOptions.() -> Unit by FinalizeOnRead {}

    override fun devNamespace(namespace: String) {
        val delegate: FinalizeOnRead<MappingNamespaceTree.Namespace> = RemapJarTask::class.getField("devNamespace")!!.getDelegate(this) as FinalizeOnRead<MappingNamespaceTree.Namespace>
        delegate.setValueIntl(LazyMutable { provider.mappings.getNamespace(namespace) })
    }

    override fun devFallbackNamespace(namespace: String) {
        val delegate: FinalizeOnRead<MappingNamespaceTree.Namespace> = RemapJarTask::class.getField("devFallbackNamespace")!!.getDelegate(this) as FinalizeOnRead<MappingNamespaceTree.Namespace>
        delegate.setValueIntl(LazyMutable { provider.mappings.getNamespace(namespace) })
    }

    override fun prodNamespace(namespace: String) {
        val delegate: FinalizeOnRead<MappingNamespaceTree.Namespace> = RemapJarTask::class.getField("prodNamespace")!!.getDelegate(this) as FinalizeOnRead<MappingNamespaceTree.Namespace>
        delegate.setValueIntl(LazyMutable { provider.mappings.getNamespace(namespace) })
    }

    override fun mixinRemap(action: MixinRemapOptions.() -> Unit) {
        val delegate: FinalizeOnRead<MixinRemapOptions.() -> Unit> = RemapJarTaskImpl::class.getField("mixinRemapOptions")!!.getDelegate(this) as FinalizeOnRead<MixinRemapOptions.() -> Unit>
        val old = delegate.value as MixinRemapOptions.() -> Unit
        mixinRemapOptions = {
            old()
            action()
        }
    }

    override var allowImplicitWildcards by FinalizeOnRead(false)

    @TaskAction
    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    fun run() {
        val prodNs = prodNamespace ?: provider.mcPatcher.prodNamespace!!
        val devNs = devNamespace ?: provider.mappings.devNamespace!!
        val devFNs = devFallbackNamespace ?: provider.mappings.devFallbackNamespace!!

        val path = provider.mappings.getRemapPath(
            devNs,
            devFNs,
            prodNs,
            prodNs
        )

        val inputFile = provider.mcPatcher.beforeRemapJarTask(this, inputFile.get().asFile.toPath())

        if (path.isEmpty()) {
            project.logger.lifecycle("[Unimined/RemapJar ${this.path}] detected empty remap path, jumping to after remap tasks")
            provider.mcPatcher.afterRemapJarTask(this, inputFile)
            afterRemap(inputFile)
            return
        }

        project.logger.lifecycle("[Unimined/RemapJar ${this.path}] remapping output ${inputFile.name} from $devNs/$devFNs to $prodNs")
        project.logger.info("[Unimined/RemapJar ${this.path}]    $devNs -> ${path.joinToString(" -> ") { it.name }}")
        var prevTarget = inputFile
        var prevNamespace = devNs
        var prevPrevNamespace = devFNs
        for (i in path.indices) {
            val step = path[i]
            project.logger.info("[Unimined/RemapJar ${this.path}]    $step")
            val nextTarget = temporaryDir.toPath().resolve("${inputFile.nameWithoutExtension}-temp-${step.name}.jar")
            nextTarget.deleteIfExists()

            val mc = provider.getMinecraft(
                prevNamespace,
                prevPrevNamespace
            )
            val classpath = provider.mods.getClasspathAs(
                prevNamespace,
                prevPrevNamespace,
                provider.sourceSet.compileClasspath.files
            ).map { it.toPath() }.filter { it.exists() && !provider.isMinecraftJar(it) }

            project.logger.debug("[Unimined/RemapJar ${path}] classpath: ")
            classpath.forEach {
                project.logger.debug("[Unimined/RemapJar ${path}]    $it")
            }

            remapToInternal(prevTarget, nextTarget, prevNamespace, step, (classpath + listOf(mc)).toTypedArray())

            prevTarget = nextTarget
            prevPrevNamespace = prevNamespace
            prevNamespace = step
        }
        project.logger.info("[Unimined/RemapJar ${path}] after remap tasks started ${System.currentTimeMillis()}")
        provider.mcPatcher.afterRemapJarTask(this, prevTarget)
        afterRemap(prevTarget)
        project.logger.info("[Unimined/RemapJar ${path}] after remap tasks finished ${System.currentTimeMillis()}")
    }

    private fun afterRemap(afterRemapJar: Path) {
        // merge in manifest from input jar
        afterRemapJar.readZipInputStreamFor("META-INF/MANIFEST.MF", false) { inp ->
            // write to temp file
            val inpTmp = temporaryDir.toPath().resolve("input-manifest.MF")
            inpTmp.outputStream(StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING).use { out ->
                inp.copyTo(out)
            }
            this.manifest {
                it.from(inpTmp)
            }
        }
        // copy into output
        from(project.zipTree(afterRemapJar))
        copy()
    }

    @Suppress("UNNECESSARY_NOT_NULL_ASSERTION")
    protected open fun remapToInternal(
        from: Path,
        target: Path,
        fromNs: MappingNamespaceTree.Namespace,
        toNs: MappingNamespaceTree.Namespace,
        classpathList: Array<Path>
    ) {
        project.logger.info("[Unimined/RemapJar ${path}] remapping $fromNs -> $toNs (start time: ${System.currentTimeMillis()})")
        val remapperB = TinyRemapper.newRemapper()
            .withMappings(
                provider.mappings.getTRMappings(
                    fromNs to toNs,
                    false
                )
            )
            .skipLocalVariableMapping(true)
            .ignoreConflicts(true)
            .threads(Runtime.getRuntime().availableProcessors())
        val classpath = KotlinClasspathService.getOrCreateIfRequired(project)
        if (classpath != null) {
            remapperB.extension(KotlinRemapperClassloader.create(classpath).tinyRemapperExtension)
        }
        val betterMixinExtension = MixinRemapExtension(
            project.gradle.startParameter.logLevel,
            allowImplicitWildcards
        )
        betterMixinExtension.enableBaseMixin()
        mixinRemapOptions(betterMixinExtension)
        remapperB.extension(betterMixinExtension)
        provider.minecraftRemapper.tinyRemapperConf(remapperB)
        val remapper = remapperB.build()
        val tag = remapper.createInputTag()
        project.logger.debug("[Unimined/RemapJar ${path}] input: $from")
        betterMixinExtension.readClassPath(remapper, *classpathList).thenCompose {
            project.logger.info("[Unimined/RemapJar ${path}] reading input: $from (time: ${System.currentTimeMillis()})")
            betterMixinExtension.readInput(remapper, tag, from)
        }.thenRun {
            project.logger.info("[Unimined/RemapJar ${path}] writing output: $target (time: ${System.currentTimeMillis()})")
            target.parent.createDirectories()
            try {
                OutputConsumerPath.Builder(target).build().use {
                    it.addNonClassFiles(
                        from,
                        remapper,
                        listOf(
                            AccessWidenerApplier.AwRemapper(
                                if (fromNs.named) "named" else fromNs.name,
                                if (toNs.named) "named" else toNs.name
                            ),
                            AccessTransformerApplier.AtRemapper(
                                project.logger,
                                remapATToLegacy.getOrElse((provider.mcPatcher as? ForgeLikePatcher<*>)?.remapAtToLegacy == true)!!
                            ),
                        )
                    )
                    remapper.apply(it, tag)
                }
            } catch (e: Exception) {
                target.deleteIfExists()
                throw e
            }
            remapper.finish()
            target.openZipFileSystem(mapOf("mutable" to true)).use {
                betterMixinExtension.insertExtra(tag, it)
            }
            project.logger.info("[Unimined/RemapJar ${path}] remapped $fromNs -> $toNs (end time: ${System.currentTimeMillis()})")
        }.join()
    }

}