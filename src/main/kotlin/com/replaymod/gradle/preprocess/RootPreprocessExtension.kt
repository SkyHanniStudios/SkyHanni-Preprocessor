package com.replaymod.gradle.preprocess

import org.gradle.api.Project
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.model.ObjectFactory
import org.gradle.kotlin.dsl.property
import java.io.File

open class RootPreprocessExtension(project: Project, objects: ObjectFactory) : ProjectGraphNodeDSL {
    val mainProjectFile: RegularFileProperty = objects.fileProperty()

    val strictExtraMappings = objects.property<Boolean>() // defaults to `false` for backwards compatibility

    var rootNode: ProjectGraphNode? = null
        get() = field ?: linkNodes()?.also { field = it }

    private val nodes = mutableSetOf<Node>()

    init {
        mainProjectFile.convention(project.layout.projectDirectory.file("versions/mainProject"))
    }

    fun createNode(project: String, mcVersion: Int, mappings: String): Node {
        return Node(project, mcVersion, mappings).also { nodes.add(it) }
    }

    private fun linkNodes(): ProjectGraphNode? {
        val first = nodes.firstOrNull() ?: return null
        val visited = mutableSetOf<Node>()
        fun Node.breadthFirstSearch(): ProjectGraphNode {
            val graphNode = ProjectGraphNode(project, mcVersion, mappings)
            links.forEach { (otherNode, extraMappings) ->
                if (visited.add(otherNode)) {
                    graphNode.links.add(Pair(otherNode.breadthFirstSearch(), extraMappings))
                }
            }
            return graphNode
        }
        return first.breadthFirstSearch()
    }

    override fun addNode(project: String, mcVersion: Int, mappings: String, extraMappings: File?, invertMappings: Boolean, patternMappings: File?): ProjectGraphNode {
        check(rootNode == null) { "Only one root node may be set." }
        check(extraMappings == null) { "Cannot add extra mappings to root node." }
        return ProjectGraphNode(project, mcVersion, mappings).also { rootNode = it }
    }
}

class Node(
    val project: String,
    val mcVersion: Int,
    val mappings: String,
) {
    internal val links = mutableMapOf<Node, Triple<File?, Boolean, File?>>()

    fun link(other: Node, extraMappings: File? = null, patternMappings: File? = null) {
        this.links[other] = Triple(extraMappings, false, patternMappings)
        other.links[this] = Triple(extraMappings, true, patternMappings)
    }
}

interface ProjectGraphNodeDSL {
    operator fun String.invoke(mcVersion: Int, mappings: String, extraMappings: File? = null, configure: ProjectGraphNodeDSL.() -> Unit = {}) {
        addNode(this, mcVersion, mappings, extraMappings).configure()
    }

    fun addNode(project: String, mcVersion: Int, mappings: String, extraMappings: File? = null, invertMappings: Boolean = false, patternMappings: File? = null): ProjectGraphNodeDSL
}

open class ProjectGraphNode(
    val project: String,
    val mcVersion: Int,
    val mappings: String,
    val links: MutableList<Pair<ProjectGraphNode, Triple<File?, Boolean, File?>>> = mutableListOf(),
) : ProjectGraphNodeDSL {
    override fun addNode(project: String, mcVersion: Int, mappings: String, extraMappings: File?, invertMappings: Boolean, patternMappings: File?): ProjectGraphNodeDSL =
        ProjectGraphNode(project, mcVersion, mappings).also { links.add(Pair(it, Triple(extraMappings, invertMappings, patternMappings))) }

    fun findNode(project: String): ProjectGraphNode? = if (project == this.project) {
        this
    } else {
        links.map { it.first.findNode(project) }.find { it != null }
    }

    fun findParent(node: ProjectGraphNode): Pair<ProjectGraphNode, Triple<File?, Boolean, File?>>? = if (node == this) {
        null
    } else {
        links.map { (child, extraMappings) ->
            if (child == node) {
                Pair(this, extraMappings)
            } else {
                child.findParent(node)
            }
        }.find { it != null }
    }
}
