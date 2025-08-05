package com.replaymod.gradle.preprocess

import com.replaymod.gradle.remap.PatternMapping
import com.replaymod.gradle.remap.Transformer
import com.replaymod.gradle.remap.legacy.LegacyMapping
import com.replaymod.gradle.remap.legacy.LegacyMappingSetModelFactory
import net.fabricmc.mappingio.MappedElementKind
import net.fabricmc.mappingio.MappingReader
import net.fabricmc.mappingio.tree.MappingTree
import net.fabricmc.mappingio.tree.MemoryMappingTree
import org.cadixdev.lorenz.MappingSet
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.FileCollection
import org.gradle.api.file.FileSystemOperations
import org.gradle.api.file.ProjectLayout
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputDirectories
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.internal.cc.base.logger
import org.gradle.kotlin.dsl.mapProperty
import org.gradle.kotlin.dsl.property
import org.gradle.work.ChangeType
import org.gradle.work.Incremental
import org.gradle.work.InputChanges
import org.jetbrains.kotlin.backend.common.peek
import org.jetbrains.kotlin.backend.common.pop
import org.jetbrains.kotlin.backend.common.push
import org.slf4j.LoggerFactory
import java.io.File
import java.io.FileInputStream
import java.io.ObjectInputStream
import java.io.Serializable
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.Paths
import java.util.regex.Pattern
import javax.inject.Inject
import kotlin.io.path.extension
import kotlin.io.path.isDirectory
import kotlin.io.path.isRegularFile
import kotlin.io.path.pathString
import kotlin.io.path.readLines
import kotlin.io.path.readText

data class Keywords(
    val disableRemap: String,
    val enableRemap: String,
    val `if`: String,
    val ifdef: String,
    val elseif: String,
    val `else`: String,
    val endif: String,
    val eval: String,
) : Serializable

private val pathDelimiter = Paths.get("\\").pathString

@CacheableTask
open class PreprocessTask @Inject constructor(
    private val layout: ProjectLayout,
    private val fsops: FileSystemOperations,
    objects: ObjectFactory,
) : DefaultTask() {
    companion object {
        @JvmStatic
        val DEFAULT_KEYWORDS = Keywords(
            disableRemap = "//#disable-remap",
            enableRemap = "//#enable-remap",
            `if` = "//#if",
            ifdef = "//#ifdef",
            elseif = "//#elseif",
            `else` = "//#else",
            endif = "//#endif",
            eval = "//$$"
        )

        @JvmStatic
        val CFG_KEYWORDS = Keywords(
            disableRemap = "##disable-remap",
            enableRemap = "##enable-remap",
            `if` = "##if",
            ifdef = "##ifdef",
            elseif = "##elseif",
            `else` = "##else",
            endif = "##endif",
            eval = "#$$"
        )

        private val LOGGER = LoggerFactory.getLogger(PreprocessTask::class.java)
    }

    data class InOut(
        val source: FileCollection,
        val generated: File,
        val overwrites: File?,
    )

    @Internal
    var entries: MutableList<InOut> = mutableListOf()

    @get:Incremental
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val sourceFiles: ConfigurableFileCollection = objects.fileCollection()

    @get:Incremental
    @get:InputFiles
    @get:PathSensitive(PathSensitivity.RELATIVE)
    val overwriteFiles: ConfigurableFileCollection = objects.fileCollection()

    @OutputDirectories
    fun getGeneratedDirectories(): List<File> = entries.map { it.generated }

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var sourceMappings: File? = null

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    var destinationMappings: File? = null

    // Note: Requires that source and destination mappings files to be in `tiny` format.
    @Input
    @Optional // required if source or destination mappings have more than two namespaces (optional for backwards compat)
    val intermediateMappingsName = project.objects.property<String>()

    @Input
    val strictExtraMappings = project.objects.property<Boolean>().convention(false)

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    val mapping: RegularFileProperty = objects.fileProperty()

    @Input
    var reverseMapping: Boolean = false

    @InputFile
    @Optional
    @PathSensitive(PathSensitivity.NONE)
    val mappingsList: RegularFileProperty = objects.fileProperty()

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val jdkHome = objects.directoryProperty()

    @InputDirectory
    @Optional
    @PathSensitive(PathSensitivity.RELATIVE)
    val remappedjdkHome = objects.directoryProperty()

    @Internal   // These are Inputs, but they will be handled with the source changes anyway
    var classpath: FileCollection? = null

    @Internal   // These are Inputs, but they will be handled with the source changes anyway
    var remappedClasspath: FileCollection? = null

    @Input
    val vars = objects.mapProperty<String, Int>()

    @Input
    val keywords = objects.mapProperty<String, Keywords>()

    @Input
    @Optional
    val patternAnnotation = objects.property<String>()

    @Input
    @Optional
    val manageImports = objects.property<Boolean>()

    @Input
    var incrementalFlag = false

    @Input
    var projectNaming = ""
        set(value) {
            normalProjectImport = "import $value"
            commentedProjectImport = "//$$ import $value"
            packageName = "package $value"
            field = value
        }

    private var normalProjectImport = "import $projectNaming"
    private var commentedProjectImport = "//$$ import $projectNaming"
    private var packageName = "package $projectNaming"

    fun entry(source: FileCollection, generated: File, overwrites: File) {
        entries.add(InOut(source, generated, overwrites))
        sourceFiles.from(source)
        overwriteFiles.from(overwrites)
    }

    @TaskAction
    fun preprocess(inputChanges: InputChanges) {

        val mapping = mapping.takeIfThereAndNotEmpty()?.get()?.asFile

        if (!inputChanges.isIncremental || !incrementalFlag) {
            logger.lifecycle("Full Preprocess run")
            preprocessAll(mapping, entries)
            return
        }

        val sourceChanges = inputChanges.getFileChanges(sourceFiles).associate { it.file to it.changeType }
        val overwriteChanges = inputChanges.getFileChanges(overwriteFiles).associate { it.file to it.changeType }
        val changedFiles = sourceChanges + overwriteChanges
        logger.lifecycle("Incremental Preprocess run with ${changedFiles.size} changes")

        val sourceFiles: List<Entry> = changedFiles.flatMap { (file, change) ->
            val (inOut, inBase) = entries.firstNotNullOfOrNull { entry ->
                entry.source.files.find { it.isContained(file) }?.let { entry to it }
                    ?: entry.overwrites?.isContained(file)?.let { entry to null }
            } ?: return@flatMap emptyList()

            val outBasePath = inOut.generated.toPath()
            val overwritesBasePath = inOut.overwrites?.toPath()
            val inBasePath = inBase?.toPath()

            val sourcePath = inBasePath ?: overwritesBasePath ?: return@flatMap emptyList()

            if (change == ChangeType.REMOVED) {
                fsops.delete {
                    delete(outBasePath.resolve(sourcePath.relativize(file.toPath())))
                }
                return@flatMap emptyList()
            }

            layout.files(file).asFileTree.map { f ->
                val relPath = sourcePath.relativize(f.toPath())
                Entry(relPath.toString(), inBasePath, outBasePath, overwritesBasePath)
            }

        }

        logger.debug("sourceFiles: {}", sourceFiles)

        val dependencies = mutableListOf<Entry>()

        val solvedDependencies = sourceFiles.map { it.resolveOut }.toMutableSet()

        val walkDownCache = mutableMapOf<Path, String>()

        val convertedCompanyName = projectNaming.convertedDotToPath()

        for (entry in sourceFiles) {
            if (!entry.isJavaOrKotlin) continue

            val walkDown = walkDownCache.computeIfAbsent(entry.outBase) { path ->

                Files.walk(path).filter { Files.isDirectory(it) }.filter { it.endsWith(convertedCompanyName) }
                    .findFirst().orElse(null)?.let {
                        path.relativize(it).pathString + pathDelimiter
                    } ?: run {
                    logger.error("Failed to find walkDown of '$convertedCompanyName' in entry '${entry.outBase}'")
                    ""
                }
            }
            cascadingDependencyResolution(walkDown, entry, solvedDependencies, dependencies)
        }

        logger.debug("dependencies: {}", dependencies)
        logger.lifecycle("Touching ${sourceFiles.size + dependencies.size} files")

        preprocess(mapping, sourceFiles, dependencies)
    }

    private fun String.convertedDotToPath(): String = Paths.get(replace('.', '\\')).pathString

    fun preprocessAll(mapping: File?, entries: List<InOut>) {

        val sourceFiles = entries.flatMap { inOut ->
            val outBasePath = inOut.generated.toPath()
            val overwritesBasePath = inOut.overwrites?.toPath()
            inOut.source.flatMap { inBase ->
                val inBasePath = inBase.toPath()
                layout.files(inBase).asFileTree.map { file ->
                    val relPath = inBasePath.relativize(file.toPath())
                    Entry(relPath.pathString, inBasePath, outBasePath, overwritesBasePath)
                }
            }
        }

        val onlyOverwrite = entries.filter { inOut -> inOut.overwrites != null }.flatMap { inOut ->
            val base = inOut.overwrites!!
            if (inOut.source.files.any { it.isContained(base) }) return@flatMap emptyList()
            layout.files(base).asFileTree.mapNotNull { file ->
                if (file.name.endsWith(".java") || file.name.endsWith(".kt")) {
                    val relPath = base.toPath().relativize(file.toPath())
                    Entry(relPath.pathString, inOut.source.first().toPath(), inOut.generated.toPath(), base.toPath())
                } else null
            }
        }

        preprocess(mapping, sourceFiles + onlyOverwrite, emptyList())
    }

    private data class Entry(
        val relPath: String,
        val inBase: Path?,
        val outBase: Path,
        private val overwritesBase: Path?,
    ) {

        val resolveBase: Path? = inBase?.resolve(relPath)
        val resolveOut: Path = outBase.resolve(relPath)
        val resolveOverwrite: Path? = overwritesBase?.resolve(relPath)
        val isJavaOrKotlin = relPath.endsWith(".java") || relPath.endsWith(".kt")
        val hasOverwrite = resolveOverwrite?.isRegularFile() == true
        val hasBase = resolveBase?.isRegularFile() == true

        init {
            require(hasBase || hasOverwrite) {
                "Entry without any source file. Rel: '$relPath', inBase: '$inBase', overwrite: '$overwritesBase', outBase : '$outBase'"
            }
        }

        val sourceBase: Path = (if (hasOverwrite) overwritesBase else inBase)!!

        val resolvedSource: Path = sourceBase.resolve(relPath)

        val resolvedFuture: Path = if (resolveOut.isRegularFile()) resolveOut else resolveOverwrite ?: resolveBase!!

        fun makeCopy(relativePath: String) = Entry(relativePath, inBase, outBase, overwritesBase)
        fun makeCopyAbsoluteBase(absolutePath: Path) = makeCopy(inBase!!.relativize(absolutePath).pathString)
        fun makeCopyAbsoluteOut(absolutePath: Path) = try {
            makeCopy(outBase.relativize(absolutePath).pathString)
        } catch (e: Throwable) {
            logger.error("Entry failed", e)
            null
        }

        fun findFirstDirOrFileUnderOut(prefix: String, fileLocation: String): Path {
            val combi = Paths.get(prefix, fileLocation)
            val baseResolved = outBase.resolve(combi)
            if (baseResolved.isDirectory()) {
                return baseResolved
            }
            return listOf(".kt", ".java").map {
                outBase.resolve(combi.pathString + it)
            }.firstOrNull { it.isRegularFile() }
                ?: findFirstDirOrFileUnderOut(prefix, fileLocation.substringBeforeLast(pathDelimiter, ""))
        }
    }

    private fun String.resolveImport(): String? = when {
        startsWith(normalProjectImport) -> removeImportAliases(normalProjectImport.length + 1)
        startsWith(commentedProjectImport) -> removeImportAliases(commentedProjectImport.length + 1)
        else -> null
    }

    private fun String.removeImportAliases(prefixCount: Int): String {
        val end = indexOf(" as ", prefixCount)
        return if (end == -1) substring(prefixCount).trim().trimEnd(';') else substring(prefixCount, end).trim()
            .trimEnd(';')
    }

    private fun cascadingDependencyResolution(
        prefix: String,
        entry: Entry,
        solved: MutableSet<Path>,
        result: MutableList<Entry>,
        useFuture: Boolean = false,
    ) {
        val lines = (if (useFuture) entry.resolvedFuture else entry.resolvedSource).readLines()

        fun handleDirectory(dependency: Path) {
            val subFiles = Files.newDirectoryStream(dependency) {
                it.isRegularFile() && (it.extension == "java" || it.extension == "kt")
            }.toList()
            solved.add(dependency)
            val newFiles = subFiles.filter { !solved.contains(it) }
            solved.addAll(newFiles)
            val newEntries = newFiles.mapNotNull { entry.makeCopyAbsoluteOut(it) }
            result.addAll(newEntries)
            newEntries.forEach {
                cascadingDependencyResolution(prefix, it, solved, result, true)
            }
        }

        val rawPackageLine = lines.first()

        val realPackageRange =
            (packageName.length + 1) until (rawPackageLine.indexOf(' ', packageName.length + 1).takeIf { it != -1 }
                ?: rawPackageLine.length)

        val packageIn = if (realPackageRange.isEmpty()) "" else rawPackageLine.substring(realPackageRange).trim()
            .trimEnd(';')

        val packagePath = entry.outBase.resolve(Paths.get(prefix, packageIn.convertedDotToPath()))

        if (!solved.contains(packagePath)) {
            handleDirectory(packagePath)
        }

        for (line in lines.drop(2)) {
            val import = line.resolveImport()?.convertedDotToPath() ?: continue
            val dependency = entry.findFirstDirOrFileUnderOut(prefix, import)

            if (solved.contains(dependency)) continue

            if (dependency.isDirectory()) {
                handleDirectory(dependency)
            } else {
                solved.add(dependency)
                val newEntry = entry.makeCopyAbsoluteOut(dependency) ?: continue
                result.add(newEntry)
                cascadingDependencyResolution(prefix, newEntry, solved, result, true)
            }
        }
    }

    private fun File.isContained(file: File): Boolean {
        val filePath: Path = file.toPath().normalize()
        val rootPath: Path = this.toPath().normalize()
        return filePath.startsWith(rootPath)
    }

    private fun preprocess(mapping: File?, sourceFiles: List<Entry>, alreadyProcessedFiles: Collection<Entry>) {

        fsops.delete {
            delete(sourceFiles.map(Entry::resolveOut))
        }

        var mappedSources: Map<String, Pair<String, List<Pair<Int, String>>>>? = null

        val classpath = classpath
        val sourceMappingsFile = sourceMappings
        val destinationMappingsFile = destinationMappings
        val mappings = makeMappings(classpath, sourceMappingsFile, destinationMappingsFile, mapping)

        if (mappings != null) {
            classpath!!
            val mappingsList: List<PatternMapping> = mappingsList.takeIfThereAndNotEmpty()?.let {
                ObjectInputStream(FileInputStream(mappingsList.get().asFile)).use { (it.readObject() as List<*>).filterIsInstance<PatternMapping>() }
            } ?: emptyList()

            val javaTransformer = Transformer(mappings, mappingsList)
            javaTransformer.verboseCompilerMessages = logger.isInfoEnabled
            javaTransformer.patternAnnotation = patternAnnotation.orNull
            javaTransformer.manageImports = manageImports.getOrElse(false)
            javaTransformer.jdkHome = jdkHome.orNull?.asFile
            javaTransformer.remappedJdkHome = remappedjdkHome.orNull?.asFile
            LOGGER.debug("Remap Classpath:")
            javaTransformer.classpath = classpath.files.mapNotNull {
                if (it.exists()) {
                    it.absolutePath.also(LOGGER::debug)
                } else {
                    LOGGER.debug("{} (file does not exist)", it)
                    null
                }
            }.toTypedArray()
            LOGGER.debug("Remapped Classpath:")
            javaTransformer.remappedClasspath = remappedClasspath?.files?.mapNotNull {
                if (it.exists()) {
                    it.absolutePath.also(LOGGER::debug)
                } else {
                    LOGGER.debug("{} (file does not exist)", it)
                    null
                }
            }?.toTypedArray()

            val sources = mutableMapOf<String, String>()
            val processedSources = mutableMapOf<String, String>()
            for (entry in sourceFiles) {
                if (!entry.isJavaOrKotlin || !entry.hasBase) continue
                val text = String(Files.readAllBytes(entry.resolveBase!!))
                sources[entry.relPath] = text
                val lines = text.lines()
                val kws = keywords.get().entries.find { (ext, _) -> entry.relPath.endsWith(ext) }
                if (kws != null) {
                    processedSources[entry.relPath] = CommentPreprocessor(vars.get()).convertSource(
                        kws.value,
                        lines,
                        lines.map { Pair(it, emptyList()) },
                        entry.relPath
                    ).joinToString("\n")
                }
            }

            for (entry in sourceFiles) {
                if (!entry.isJavaOrKotlin || !entry.hasOverwrite) continue
                processedSources[entry.relPath.toString()] = entry.resolveOverwrite?.readText() ?: continue
            }

            val reference = alreadyProcessedFiles.filter { it.isJavaOrKotlin && it.hasBase }
                .associate { it.relPath.toString() to it.resolveBase!!.readText() }

            mappedSources = javaTransformer.remap(sources, reference, processedSources)
        }

        val commentPreprocessor = CommentPreprocessor(vars.get())
        sourceFiles.forEach { entry ->
            if (entry.hasOverwrite) return@forEach
            val relPath = entry.relPath
            val file = entry.resolveBase?.toFile() ?: return@forEach
            val outFile = entry.resolveOut.toFile()
            val kws = keywords.get().entries.find { (ext, _) -> file.name.endsWith(ext) }
            if (kws != null) {
                val javaTransform = { lines: List<String> ->
                    mappedSources?.get(relPath)?.let { (source, errors) ->
                        val errorsByLine = mutableMapOf<Int, MutableList<String>>()
                        for ((line, error) in errors) {
                            errorsByLine.getOrPut(line, ::mutableListOf).add(error)
                        }
                        source.lines().mapIndexed { index: Int, line: String ->
                            Pair(
                                line,
                                errorsByLine[index] ?: emptyList<String>()
                            )
                        }
                    } ?: lines.map { Pair(it, emptyList()) }
                }
                commentPreprocessor.convertFile(kws.value, file, outFile, javaTransform)
            } else {
                fsops.copy {
                    from(file)
                    into(outFile.parentFile)
                }
            }
        }

        if (commentPreprocessor.fail) {
            throw GradleException("Failed to remap sources. See errors above for details.")
        }
    }

    private fun makeMappings(
        classpath: FileCollection?,
        sourceMappingsFile: File?,
        destinationMappingsFile: File?,
        mapping: File?,
    ): MappingSet? =
        if (intermediateMappingsName.isPresent && classpath != null && sourceMappingsFile != null && destinationMappingsFile != null) {
            val sharedMappingsNamespace = intermediateMappingsName.get()
            val srcTree = MemoryMappingTree().also { MappingReader.read(sourceMappingsFile.toPath(), it) }
            val dstTree = MemoryMappingTree().also { MappingReader.read(destinationMappingsFile.toPath(), it) }
            if (strictExtraMappings.get()) {
                if (sharedMappingsNamespace == "srg") {
                    inferSharedClassMappings(srcTree, dstTree, sharedMappingsNamespace)
                }
                srcTree.setIndexByDstNames(true)
                dstTree.setIndexByDstNames(true)
                val extTree = mapping?.let { file ->
                    try {
                        val ast = ExtraMapping.read(file.toPath())
                        if (!reverseMapping) {
                            ast.resolve(logger, srcTree, dstTree, "named", sharedMappingsNamespace).first
                        } else {
                            ast.resolve(logger, dstTree, srcTree, "named", sharedMappingsNamespace).second
                        }
                    } catch (e: Exception) {
                        throw GradleException("Failed to parse $file: ${e.message}", e)
                    }
                } ?: MemoryMappingTree().apply { visitNamespaces("source", listOf("destination")) }
                val mrgTree = mergeMappings(srcTree, dstTree, extTree, sharedMappingsNamespace)
                TinyReader(mrgTree, "source", "destination").read()
            } else {
                val sourceMappings = TinyReader(srcTree, "named", sharedMappingsNamespace).read()
                val destinationMappings = TinyReader(dstTree, "named", sharedMappingsNamespace).read()
                if (mapping != null) {
                    val legacyMap = LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                    val clsMap = legacyMap.splitOffClassMappings()
                    val srcMap = sourceMappings
                    val dstMap = destinationMappings
                    legacyMap.mergeBoth(
                        // The inner clsMap is to make the join work, the outer one for custom classes (which are not part of
                        // dstMap and would otherwise be filtered by the join)
                        srcMap.mergeBoth(clsMap).join(dstMap.reverse()).mergeBoth(clsMap),
                        MappingSet.create(LegacyMappingSetModelFactory())
                    )
                } else {
                    val srcMap = sourceMappings!!
                    val dstMap = destinationMappings!!
                    srcMap.join(dstMap.reverse())
                }
            }
        } else if (!intermediateMappingsName.isPresent && classpath != null && (mapping != null || sourceMappings != null && destinationMappings != null)) {
            if (mapping != null) {
                if (sourceMappings != null && destinationMappings != null) {
                    val legacyMap = LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                    val clsMap = legacyMap.splitOffClassMappings()
                    val srcMap = sourceMappings!!.readMappings()
                    val dstMap = destinationMappings!!.readMappings()
                    legacyMap.mergeBoth(
                        // The inner clsMap is to make the join work, the outer one for custom classes (which are not part of
                        // dstMap and would otherwise be filtered by the join)
                        srcMap.mergeBoth(clsMap).join(dstMap.reverse()).mergeBoth(clsMap),
                        MappingSet.create(LegacyMappingSetModelFactory())
                    )
                } else {
                    LegacyMapping.readMappingSet(mapping.toPath(), reverseMapping)
                }
            } else {
                val srcMap = sourceMappings!!.readMappings()
                val dstMap = destinationMappings!!.readMappings()
                srcMap.join(dstMap.reverse())
            }
        } else {
            null
        }

    /**
     * Tries to infer shared classes based on shared members.
     *
     * Forge uses intermediate mappings ("SRG", same as the original file format they came in) which do not contain
     * intermediate names for classes, only methods and fields. As such, one would have to manually declare mappings
     * for all classes one cares about.
     * It does however still track methods and fields even when the class name changes, so we can make use of those
     * to infer a good deal of class mappings automatically.
     *
     * This method infers these mappings, and updates the input trees to use them.
     */
    private fun inferSharedClassMappings(
        srcTree: MemoryMappingTree,
        dstTree: MemoryMappingTree,
        sharedNamespace: String,
    ) {
        val srcNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstNsId = dstTree.getNamespaceId(sharedNamespace)

        val done = mutableSetOf<String>()

        // Check for classes which didn't change their name (presumably)
        for (srcCls in srcTree.classes) {
            val srcName = srcCls.getName(srcNsId)!!
            if (dstTree.getClass(srcName, dstNsId) != null) {
                done.add(srcName)
            }
        }

        // This isn't entirely straightforward though because inherited methods (and their synthetic overload methods)
        // have the same names as super methods, so we can't just assume a match on the first shared method.
        // Instead, we'll do multiple rounds and in each one we only pair those classes that unambiguously match.
        var nextSharedId = 0
        do {
            val doneBeforeRound = done.size

            val srcMemberToClass = mutableMapOf<String, MutableList<String>>()
            for (cls in srcTree.classes) {
                val clsName = cls.getName(srcNsId)!!
                if (clsName in done) {
                    continue
                }
                for (field in cls.fields) {
                    val name = field.getName(srcNsId)!!
                    if (!name.startsWith("field_")) continue
                    srcMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
                for (method in cls.methods) {
                    val name = method.getName(srcNsId)!!
                    if (!name.startsWith("func_")) continue
                    srcMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
            }
            val dstMemberToClass = mutableMapOf<String, MutableList<String>>()
            for (cls in dstTree.classes) {
                val clsName = cls.getName(dstNsId)!!
                if (clsName in done) {
                    continue
                }
                for (field in cls.fields) {
                    val name = field.getName(dstNsId)!!
                    if (!name.startsWith("field_")) continue
                    dstMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
                for (method in cls.methods) {
                    val name = method.getName(dstNsId)!!
                    if (!name.startsWith("func_")) continue
                    dstMemberToClass.getOrPut(name, ::mutableListOf).add(clsName)
                }
            }

            val srcMappings = tryInferMapping(srcTree, srcNsId, dstMemberToClass, done)
            val dstMappings = tryInferMapping(dstTree, dstNsId, srcMemberToClass, done)

            for ((srcName, dstNames) in srcMappings) {
                if (dstNames.isEmpty()) {
                    continue
                }
                if (dstNames.size > 1) {
                    // println("Multiple dst classes for $srcName: $dstNames")
                    continue
                }
                val dstName = dstNames.single()

                val revSrcNames = dstMappings.getValue(dstName)
                assert(revSrcNames.isNotEmpty())
                if (revSrcNames.size > 1) {
                    // println("Multiple src classes for $dstName: $revSrcNames")
                    continue
                }
                val revSrcName = revSrcNames.single()
                if (revSrcName != srcName) {
                    // println("Conflicting mappings $srcName -> $dstName -> $revSrcName")
                    continue
                }

                val srcCls = srcTree.getClass(srcName, srcNsId)!!
                val dstCls = dstTree.getClass(dstName, dstNsId)!!

                val sharedName = "class_${nextSharedId++}"
                // println("Discovered mapping $srcName -> $dstName, assigning $sharedName")
                srcCls.setDstName(sharedName, srcNsId)
                dstCls.setDstName(sharedName, dstNsId)
                done.add(sharedName)
            }
        } while (done.size > doneBeforeRound)
    }

    private fun tryInferMapping(
        srcTree: MappingTree,
        srcNsId: Int,
        dstMemberToClass: Map<String, List<String>>,
        done: Set<String>,
    ): Map<String, Collection<String>> {
        val results = mutableMapOf<String, Collection<String>>()
        for (srcCls in srcTree.classes) {
            val srcName = srcCls.getName(srcNsId)!!
            if (srcName in done) {
                continue
            }

            val candidates = mutableMapOf<String, Int>()

            for (srcField in srcCls.fields) {
                for (dstCls in dstMemberToClass[srcField.getName(srcNsId)!!] ?: emptyList()) {
                    candidates.compute(dstCls) { _, n -> (n ?: 0) + 1 }
                }
            }
            for (srcMethod in srcCls.methods) {
                for (dstCls in dstMemberToClass[srcMethod.getName(srcNsId)!!] ?: emptyList()) {
                    candidates.compute(dstCls) { _, n -> (n ?: 0) + 1 }
                }
            }

            if (candidates.isEmpty()) {
                results[srcName] = emptyList()
                continue
            }

            val (bestName, bestCount) = candidates.maxBy { it.value }
            if (candidates.all { (name, count) -> name === bestName || count < bestCount }) {
                results[srcName] = listOf(bestName)
            } else {
                results[srcName] = candidates.keys
            }
        }
        return results
    }

    private fun mergeMappings(
        srcTree: MappingTree,
        dstTree: MappingTree,
        extTree: MemoryMappingTree,
        sharedNamespace: String,
    ): MappingTree {
        val srcNamedNsId = srcTree.getNamespaceId("named")
        val srcSharedNsId = srcTree.getNamespaceId(sharedNamespace)
        val dstSharedNsId = dstTree.getNamespaceId(sharedNamespace)
        val dstNamedNsId = dstTree.getNamespaceId("named")
        val extSrcNsId = extTree.getNamespaceId("source")
        val extDstNsId = extTree.getNamespaceId("destination")

        val tmpTree = MemoryMappingTree()
        tmpTree.visitNamespaces(dstTree.srcNamespace, dstTree.dstNamespaces)
        val mrgTree = MemoryMappingTree()
        mrgTree.visitNamespaces("source", listOf("destination"))

        fun injectExtraMembers(extCls: MappingTree.ClassMapping) {
            for (extField in extCls.fields) {
                val srcName = extField.getName(extSrcNsId)
                val srcDesc = extField.getDesc(extSrcNsId)
                if (srcDesc == null) {
                    logger.error(
                        "Owner ${extCls.getName(extSrcNsId)} of field $srcName does not appear to have any mappings. " +
                                "As such, you must provide the full signature of this method manually " +
                                "(if it does not change across versions, providing it for either version is sufficient)."
                    )
                    continue
                }
                mrgTree.visitField(srcName, srcDesc)
                mrgTree.visitDstName(MappedElementKind.FIELD, 0, extField.getName(extDstNsId))
            }
            for (extMethod in extCls.methods) {
                val srcName = extMethod.getName(extSrcNsId)
                val srcDesc = extMethod.getDesc(extSrcNsId)
                if (srcDesc == null) {
                    logger.error(
                        "Owner ${extCls.getName(extSrcNsId)} of method $srcName does not appear to have any mappings. " +
                                "As such, you must provide the full signature of this method manually " +
                                "(if it does not change across versions, providing it for either version is sufficient)."
                    )
                    continue
                }
                mrgTree.visitMethod(srcName, srcDesc)
                mrgTree.visitDstName(MappedElementKind.METHOD, 0, extMethod.getName(extDstNsId))
            }
        }

        for (srcCls in srcTree.classes) {
            val extCls = extTree.removeClass(srcCls.getName(srcNamedNsId))
            val dstCls = if (extCls != null) {
                val dstName = extCls.getName(extDstNsId)
                dstTree.getClass(dstName, dstNamedNsId) ?: run {
                    tmpTree.visitClass(dstName)
                    tmpTree.visitDstName(MappedElementKind.CLASS, dstNamedNsId, dstName)
                    tmpTree.getClass(dstName)!!
                }
            } else {
                dstTree.getClass(srcCls.getName(srcSharedNsId), dstSharedNsId) ?: continue
            }
            mrgTree.visitClass(srcCls.getName(srcNamedNsId))
            mrgTree.visitDstName(MappedElementKind.CLASS, 0, dstCls.getName(dstNamedNsId))
            for (srcField in srcCls.fields) {
                val extField =
                    extCls?.getField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId), extSrcNsId)
                if (extField != null) {
                    extCls.removeField(extField.srcName, extField.srcDesc)
                    mrgTree.visitField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId))
                    mrgTree.visitDstName(MappedElementKind.FIELD, 0, extField.getName(extDstNsId))
                    continue
                }
                val dstField =
                    dstCls.getField(srcField.getName(srcSharedNsId), srcField.getDesc(srcSharedNsId), dstSharedNsId)
                        ?: dstCls.getField(srcField.getName(srcSharedNsId), null, dstSharedNsId)
                        ?: continue
                mrgTree.visitField(srcField.getName(srcNamedNsId), srcField.getDesc(srcNamedNsId))
                mrgTree.visitDstName(MappedElementKind.FIELD, 0, dstField.getName(dstNamedNsId))
            }
            for (srcMethod in srcCls.methods) {
                val extMethod =
                    extCls?.getMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId), extSrcNsId)
                if (extMethod != null) {
                    extCls.removeMethod(extMethod.srcName, extMethod.srcDesc)
                    mrgTree.visitMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId))
                    mrgTree.visitDstName(MappedElementKind.METHOD, 0, extMethod.getName(extDstNsId))
                    continue
                }
                val dstMethod =
                    dstCls.getMethod(srcMethod.getName(srcSharedNsId), srcMethod.getDesc(srcSharedNsId), dstSharedNsId)
                        ?: dstCls.getMethod(srcMethod.getName(srcSharedNsId), null, dstSharedNsId)
                        ?: continue
                mrgTree.visitMethod(srcMethod.getName(srcNamedNsId), srcMethod.getDesc(srcNamedNsId))
                mrgTree.visitDstName(MappedElementKind.METHOD, 0, dstMethod.getName(dstNamedNsId))
            }
            if (extCls != null) {
                injectExtraMembers(extCls)
            }
        }
        for (extCls in extTree.classes) {
            mrgTree.visitClass(extCls.getName(extSrcNsId))
            mrgTree.visitDstName(MappedElementKind.CLASS, 0, extCls.getName(extDstNsId))
            injectExtraMembers(extCls)
        }
        return mrgTree
    }
}

class CommentPreprocessor(
    private val vars: Map<String, Int>,
) {
    companion object {
        private val EXPR_PATTERN = Pattern.compile("(.+)(==|!=|<=|>=|<|>)(.+)")
        private val OR_PATTERN = Pattern.quote("||").toPattern()
        private val AND_PATTERN = Pattern.quote("&&").toPattern()
    }

    var fail = false

    private fun String.evalVarOrNull() = cleanUpIfVersion().toIntOrNull() ?: vars[this]
    private fun String.evalVar() =
        cleanUpIfVersion().evalVarOrNull() ?: throw NoSuchElementException("\'$this\' is not in $vars")

    private fun String.cleanUpIfVersion(): String {
        // Verify that this could be a version
        if (!matches("[\\d._+-]+".toRegex()))
            return this

        // Version handling
        val matcher = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(this)
        if (matcher.matches()) {
            val major = matcher.group(1) ?: "0"
            val minor = matcher.group(2)?.padStart(2, '0') ?: "00"
            val patch = matcher.group(3)?.padStart(2, '0') ?: "00"
            return "$major$minor$patch"
        }

        // Underscore handling
        return if (startsWith("_")) this else replace("_", "", false)
    }

    internal fun String.evalExpr(): Boolean {
        split(OR_PATTERN).let { parts ->
            if (parts.size > 1) {
                return parts.any { it.trim().evalExpr() }
            }
        }

        split(AND_PATTERN).let { parts ->
            if (parts.size > 1) {
                return parts.all { it.trim().evalExpr() }
            }
        }

        val result = evalVarOrNull()
        if (result != null) {
            return result != 0
        }

        val matcher = EXPR_PATTERN.matcher(this)
        if (matcher.matches()) {
            val leftExpr = matcher.group(1).trim()
            val rightExpr = matcher.group(3).trim()
            val lhs = leftExpr.evalVar()
            val rhs = rightExpr.evalVar()
            return when (matcher.group(2)) {
                "==" -> lhs == rhs
                "!=" -> lhs != rhs
                ">=" -> lhs >= rhs
                "<=" -> lhs <= rhs
                ">" -> lhs > rhs
                "<" -> lhs < rhs
                else -> throw InvalidExpressionException(this)
            }
        } else {
            throw InvalidExpressionException(this)
        }
    }

    private val String.indentation: Int
        get() = takeWhile { it == ' ' }.length

    fun convertSource(
        kws: Keywords,
        lines: List<String>,
        remapped: List<Pair<String, List<String>>>,
        fileName: String,
    ): List<String> {
        val stack = mutableListOf<IfStackEntry>()
        val indentStack = mutableListOf<Int>()
        var active = true
        var remapActive = true
        var n = 0

        fun evalCondition(condition: String): Boolean {
            if (!condition.startsWith(" "))
                throw ParserException("Expected space before condition in line $n of $fileName")
            try {
                return condition.trim().evalExpr()
            } catch (e: InvalidExpressionException) {
                throw ParserException("Invalid expression \"${e.message}\" in line $n of $fileName")
            }
        }

        return lines.zip(remapped).map { (originalLine, lineMapped) ->
            val (line, errors) = lineMapped
            var ignoreErrors = false
            n++
            val trimmed = line.trim()
            val mapped = if (trimmed.startsWith(kws.`if`)) {
                val result = evalCondition(trimmed.substring(kws.`if`.length))
                stack.push(IfStackEntry(result, n, elseFound = false, trueFound = result))
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.elseif)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected elseif in line $n of $fileName")
                }
                if (stack.last().elseFound) {
                    throw ParserException("Unexpected elseif after else in line $n of $fileName")
                }

                indentStack.pop()
                indentStack.push(line.indentation)

                active = if (stack.last().trueFound) {
                    val last = stack.pop()
                    stack.push(last.copy(currentValue = false))
                    false
                } else {
                    val result = evalCondition(trimmed.substring(kws.elseif.length))
                    stack.pop()
                    stack.push(IfStackEntry(result, n, elseFound = false, trueFound = result))
                    stack.all { it.currentValue }
                }
                line
            } else if (trimmed.startsWith(kws.`else`)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected else in line $n of $fileName")
                }
                val entry = stack.pop()
                stack.push(IfStackEntry(!entry.trueFound, n, elseFound = true, trueFound = entry.trueFound))
                indentStack.pop()
                indentStack.push(line.indentation)
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.ifdef)) {
                val result = vars.containsKey(trimmed.substring(kws.ifdef.length))
                stack.push(IfStackEntry(result, n, elseFound = false, trueFound = result))
                indentStack.push(line.indentation)
                active = active && result
                line
            } else if (trimmed.startsWith(kws.endif)) {
                if (stack.isEmpty()) {
                    throw ParserException("Unexpected endif in line $n of $fileName")
                }
                stack.pop()
                indentStack.pop()
                active = stack.all { it.currentValue }
                line
            } else if (trimmed.startsWith(kws.disableRemap)) {
                if (!remapActive) {
                    throw ParserException("Remapping already disabled in line $n of $fileName")
                }
                remapActive = false
                line
            } else if (trimmed.startsWith(kws.enableRemap)) {
                if (remapActive) {
                    throw ParserException("Remapping not disabled in line $n of $fileName")
                }
                remapActive = true
                line
            } else {
                if (active) {
                    if (trimmed.startsWith(kws.eval)) {
                        line.replaceFirst((Pattern.quote(kws.eval) + " ?").toRegex(), "").let {
                            if (it.trim().isEmpty()) {
                                ""
                            } else {
                                it
                            }
                        }
                    } else if (remapActive) {
                        line
                    } else {
                        ignoreErrors = true
                        originalLine
                    }
                } else {
                    val currIndent = indentStack.peek()!!
                    if (trimmed.isEmpty()) {
                        " ".repeat(currIndent) + kws.eval
                    } else if (!trimmed.startsWith(kws.eval) && currIndent <= line.indentation) {
                        // Line has been disabled, so we want to use its non-remapped content instead.
                        // For one, the remapped content would be useless anyway since it's commented out
                        // and, more importantly, if we do not preserve it, we might permanently loose it as the
                        // remap process is only guaranteed to work on code which compiles and since we're
                        // just about to comment it out, it probably doesn't compile.
                        ignoreErrors = true
                        " ".repeat(currIndent) + kws.eval + " " + originalLine.substring(currIndent)
                    } else {
                        line
                    }
                }
            }
            if (errors.isNotEmpty() && !ignoreErrors) {
                fail = true
                for (message in errors) {
                    System.err.println("$fileName:$n: $message")
                }
            }
            mapped
        }.also {
            if (stack.isNotEmpty()) {
                throw ParserException("Missing endif on line ${stack.peek()?.lineno} of $fileName")
            }
        }
    }

    fun convertFile(
        kws: Keywords,
        inFile: File,
        outFile: File,
        remap: ((List<String>) -> List<Pair<String, List<String>>>)? = null,
    ) {
        val string = inFile.readText()
        var lines = string.lines()
        val remapped = remap?.invoke(lines) ?: lines.map { Pair(it, emptyList()) }
        try {
            lines = convertSource(kws, lines, remapped, inFile.path)
        } catch (e: Throwable) {
            if (e is ParserException) {
                throw e
            }
            throw RuntimeException("Failed to convert file $inFile", e)
        }
        outFile.parentFile.mkdirs()
        outFile.writeText(lines.joinToString("\n"))
    }

    data class IfStackEntry(
        var currentValue: Boolean,
        var lineno: Int,
        var elseFound: Boolean = false,
        var trueFound: Boolean = false,
    )

    class InvalidExpressionException(expr: String) : RuntimeException(expr)

    class ParserException(str: String) : RuntimeException(str)
}

private fun RegularFileProperty.takeIfThereAndNotEmpty() =
    if (isPresent && get().asFile.exists() && get().asFile.reader().read() != -1) this else null