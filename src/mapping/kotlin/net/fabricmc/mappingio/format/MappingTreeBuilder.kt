package net.fabricmc.mappingio.format

import net.fabricmc.mappingio.MappingVisitor
import net.fabricmc.mappingio.adapter.MappingNsRenamer
import net.fabricmc.mappingio.adapter.MappingSourceNsSwitch
import net.fabricmc.mappingio.tree.MappingTreeView
import net.fabricmc.mappingio.tree.MemoryMappingTree
import xyz.wagyourtail.unimined.api.minecraft.EnvType
import xyz.wagyourtail.unimined.util.*
import java.io.BufferedReader
import java.io.InputStream
import java.nio.file.Path
import kotlin.io.path.inputStream
import kotlin.io.path.name

class MappingTreeBuilder {
    private val tree = MemoryMappingTree()
    private var frozen by FinalizeOnWrite(false)
    private val ns = mutableSetOf<String>()
    private var side by FinalizeOnRead(EnvType.COMBINED)

    private fun checkFrozen() {
        if (frozen) {
            throw IllegalStateException("Cannot modify frozen mapping tree")
        }
    }

    private fun checkInput(input: MappingInputBuilder) {
        if (input.nsFilter.intersect(ns).isNotEmpty()) {
            throw IllegalArgumentException("Namespace ${input.nsFilter.intersect(ns)} already exists in the tree")
        }
    }

    fun side(env: EnvType) {
        checkFrozen()
        side = env
    }

    fun mappingFile(file: Path, input: MappingInputBuilder) {
        checkFrozen()
        checkInput(input)
        if (file.isZip()) {
            val found = mutableSetOf<Pair<BetterMappingFormat, String>>()
            file.forEachInZip { name, stream ->
                val reader = stream.bufferedReader()
                val header = detectHeader(reader)
                if (header != null) {
                    found.add(header to name)
                }
            }
            found.sortedWith { a, b ->
                if (a.first == b.first) {
                    a.second.compareTo(b.second)
                } else {
                    a.first.ordinal.compareTo(b.first.ordinal)
                }
            }.forEach {
                if (it.first == BetterMappingFormat.RETROGUARD) {
                    if (side == EnvType.COMBINED) throw IllegalArgumentException("Cannot use retroguard mappings in combined mode")
                    if (it.second.endsWith("_server.rgs") && side.mcp == 0) {
                        return@forEach
                    } else if (!it.second.endsWith("_server.rgs") && side.mcp == 1) {
                        return@forEach
                    }
                }
                if (it.first == BetterMappingFormat.SRG) {
                    val combined = it.second.endsWith("joined.srg")
                    if (side == EnvType.COMBINED && !combined) {
                        throw IllegalArgumentException("Cannot use srg mappings in combined mode as joined.srg is required")
                    }
                    if (!combined) {
                        if (side == EnvType.CLIENT && !it.second.endsWith("client.srg")) {
                            return@forEach
                        }
                        if (side == EnvType.SERVER && !it.second.endsWith("server.srg")) {
                            return@forEach
                        }
                    }
                }
                file.readZipInputStreamFor(it.second) { stream ->
                    mappingReaderIntl(it.second, stream.bufferedReader(), input, it.first)
                }
            }
        } else {
            file.inputStream().use {
                mappingReaderIntl(file.name, it.bufferedReader(), input)
            }
        }
    }

    fun mappingStream(name: String, stream: InputStream, input: MappingInputBuilder) {
        checkFrozen()
        checkInput(input)
        mappingReaderIntl(name, stream.bufferedReader(), input)
    }

    private fun detectHeader(reader: BufferedReader): BetterMappingFormat? {
        reader.mark(4096)
        val str = CharArray(4096).let {
            val read = reader.read(it)
            String(it, 0, read)
        }
        val type = when (str.substring(0..2)) {
            "v1\t" -> BetterMappingFormat.TINY
            "tin" -> BetterMappingFormat.TINY_2
            "PK:", "CL:", "FD:", "MD:" -> BetterMappingFormat.SRG
            ".cl",".pa",".me",".fi", ".op" -> BetterMappingFormat.RETROGUARD
            else -> {
                if (str.startsWith("{")) {
                    BetterMappingFormat.PARCHMENT
                } else if (str.startsWith("tsrg2")) {
                    BetterMappingFormat.TSRG_2
                } else if (str.startsWith("searge,name") || str.startsWith("param,name")) {
                    BetterMappingFormat.MCP
                } else if (str.split("\n")[0].contains("\"name\",\"notch\"")) {
                    BetterMappingFormat.OLD_MCP
                } else if (str.contains("class (for reference only)")) {
                    BetterMappingFormat.OLDER_MCP
                } else if (str.contains("\n\t")) {
                    BetterMappingFormat.TSRG
                } else if (str.contains(" -> ")) {
                    BetterMappingFormat.PROGUARD
                } else {
                    null
                }
            }
        }
        reader.reset()
        return type
    }

    private fun mappingReaderIntl(
        fname: String,
        reader: BufferedReader,
        input: MappingInputBuilder,
        type: BetterMappingFormat = detectHeader(reader) ?: throw IllegalArgumentException("cannot detect mapping format")
    ) {
        val visitor = input.fmv(MappingNsRenamer(MappingSourceNsSwitch(MappingDstNsFilter(tree, input.nsFilter.toList()), input.nsSource), input.nsMap))
        val preDstNs = tree.dstNamespaces ?: emptyList()
        when (type) {
            BetterMappingFormat.TINY -> Tiny1Reader.read(reader, visitor)
            BetterMappingFormat.TINY_2 -> Tiny2Reader.read(reader, visitor)
            BetterMappingFormat.MCP -> {
                when (fname.split("/", "\\").last()) {
                    "methods.csv" -> {
                        MCPReader.readMethod(
                            side,
                            reader,
                            "searge",
                            "mcp",
                            tree,
                            visitor
                        )
                    }
                    "fields.csv" -> {
                        MCPReader.readField(
                            side,
                            reader,
                            "searge",
                            "mcp",
                            tree,
                            visitor
                        )
                    }
                    "params.csv" -> {
                        MCPReader.readParam(
                            side,
                            reader,
                            "searge",
                            "mcp",
                            tree,
                            visitor
                        )
                    }
                    else -> throw IllegalArgumentException("cannot process mapping format $type for $fname")
                }
            }
            BetterMappingFormat.OLD_MCP -> {
                when (fname.split("/", "\\").last()) {
                    "classes.csv" -> {
                        OldMCPReader.readClasses(
                            side,
                            reader,
                            "official",
                            "searge",
                            "mcp",
                            visitor
                        )
                    }
                    "methods.csv" -> {
                        OldMCPReader.readMethod(
                            side,
                            reader,
                            "official",
                            "searge",
                            "mcp",
                            visitor
                        )
                    }
                    "fields.csv" -> {
                        OldMCPReader.readField(
                            side,
                            reader,
                            "official",
                            "searge",
                            "mcp",
                            visitor
                        )
                    }
                    else -> throw IllegalArgumentException("cannot process mapping format $type for $fname")
                }
            }
            BetterMappingFormat.OLDER_MCP -> {
                when (fname.split("/", "\\").last()) {
                    "methods.csv" -> {
                        OlderMCPReader.readMethod(
                            side,
                            reader,
                            "searge",
                            "mcp",
                            tree,
                            visitor
                        )
                    }
                    "fields.csv" -> {
                        OlderMCPReader.readField(
                            side,
                            reader,
                            "searge",
                            "mcp",
                            tree,
                            visitor
                        )
                    }
                    else -> throw IllegalArgumentException("cannot process mapping format $type for $fname")
                }
            }
            BetterMappingFormat.SRG -> SrgReader.read(reader, visitor)
            BetterMappingFormat.TSRG, BetterMappingFormat.TSRG_2 -> TsrgReader.read(reader, visitor)
            BetterMappingFormat.RETROGUARD -> RGSReader.read(reader, visitor)
            BetterMappingFormat.PROGUARD -> ProGuardReader.read(reader, "mojmap", "official", visitor)
            BetterMappingFormat.PARCHMENT -> ParchmentReader.read(reader, "mojmap", visitor)
            else -> {
                throw IllegalArgumentException("cannot process mapping format $type")
            }
        }
        val postDstNs = tree.dstNamespaces ?: emptyList()
        ns.addAll(postDstNs - preDstNs.toSet())
    }

    fun build(): MappingTreeView {
        frozen = true
        return tree
    }

    class MappingInputBuilder {
        val nsMap: MutableMap<String, String> = mutableMapOf()
        val nsFilter: MutableSet<String> = mutableSetOf()
        var fmv: (MappingVisitor) -> MappingVisitor = { it }
        var nsSource: String = "official"
            private set

        fun mapNs(from: String, to: String) {
            nsMap[from] = to
        }

        fun addNs(ns: String) {
            nsFilter.add(ns)
        }

        fun setSource(ns: String) {
            nsSource = ns
        }

        fun forwardVisitor(f: (MappingVisitor) -> MappingVisitor) {
            fmv = f
        }
    }

    enum class BetterMappingFormat {
        TINY,
        TINY_2,
        ENIGMA,
        SRG,
        TSRG,
        TSRG_2,
        RETROGUARD,
        MCP,
        OLD_MCP,
        OLDER_MCP,
        PROGUARD,
        PARCHMENT
        ;
    }
}