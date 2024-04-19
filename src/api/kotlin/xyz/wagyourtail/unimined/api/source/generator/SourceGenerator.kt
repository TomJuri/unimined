package xyz.wagyourtail.unimined.api.source.generator

import groovy.lang.Closure
import groovy.lang.DelegatesTo
import org.gradle.api.artifacts.Dependency
import org.gradle.api.file.FileCollection
import org.jetbrains.annotations.ApiStatus
import java.nio.file.Path

/**
 * @since 1.2.0
 */
interface SourceGenerator {

    /**
     * shared args for the decompiler
     * (flags)
     */
    var args: List<String>

    /**
     * set the decompiler to use (defaults to vineflower)
     * @since 1.2.0
     */
    fun generator(dep: Any) {
        generator(dep) {}
    }

    /**
     * set the decompiler to use (defaults to vineflower)
     * @since 1.2.0
     */
    fun generator(dep: Any, action: Dependency.() -> Unit)

    fun generator(
        dep: Any,
        @DelegatesTo(value = Dependency::class, strategy = Closure.DELEGATE_FIRST)
        action: Closure<*>
    ) {
        generator(dep) {
            action.delegate = this
            action.resolveStrategy = Closure.DELEGATE_FIRST
            action.call()
        }
    }

    @ApiStatus.Internal
    fun generate(classpath: FileCollection, inputPath: Path, outputPath: Path, linemappedPath: Path?)
}