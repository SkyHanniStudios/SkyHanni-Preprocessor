package com.replaymod.gradle.preprocess

import com.replaymod.gradle.remap.PatternMapping
import org.gradle.api.DefaultTask
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.api.tasks.InputFile
import org.gradle.api.tasks.Internal
import org.gradle.api.tasks.Optional
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import java.io.File
import java.io.FileOutputStream
import java.io.ObjectOutputStream
import javax.inject.Inject

abstract class ProcessPatternMappings @Inject constructor(objects: ObjectFactory) : DefaultTask() {

    @get:InputFile
    @get:Optional
    var mappingFile: File? = null

    @get:InputFile
    @get:Optional
    var patternMappings: File? = null

    @get:OutputFile
    val destination: RegularFileProperty = objects.fileProperty()

    @Internal
    val patternMappingsList = mutableListOf<PatternMapping>()

    @get:OutputFile
    val patternMappingsJson: RegularFileProperty = objects.fileProperty()

    @TaskAction
    fun processPatternMappings() {
        val mappingFile = mappingFile
        val patternMappings = patternMappings
        val tempMappingFile = destination.get().asFile
        val patternMappingsJson = patternMappingsJson.get().asFile
        if(mappingFile == null){
            tempMappingFile.writeText("")
            patternMappingsJson.writeText("")
            return
        }
        if (patternMappings == null) {
            mappingFile.copyTo(tempMappingFile,overwrite = true)
            patternMappingsJson.writeText("")
            return
        }
        tempMappingFile.writeText(mappingFile.readText())

        for (line in patternMappings.readLines()) {
            if (line.trim { it <= ' ' }.startsWith("#") || line.trim { it <= ' ' }.isEmpty()) continue

            val parts = line.split(" ".toRegex()).dropLastWhile { it.isEmpty() }.toTypedArray()

            require(parts.size == 5)

            val patternMapping = PatternMapping(parts[0], parts[1], parts[2], parts[3], parts[4])
            val classMapping = "${patternMapping.newClass} ${patternMapping.oldClass}"
            val methodMapping = "${patternMapping.newClass} ${patternMapping.newMethod} ${patternMapping.oldMethod}"

            tempMappingFile.appendText("\n$classMapping\n$methodMapping")
            patternMappingsList.add(patternMapping)
        }

        val out = patternMappingsJson
        ObjectOutputStream(FileOutputStream(out)).use {
            it.writeObject(patternMappingsList)
        }
    }
}