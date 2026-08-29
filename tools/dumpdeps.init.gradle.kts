import java.security.MessageDigest
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedDependencyResult

fun sha256(f: java.io.File): String {
    val md = MessageDigest.getInstance("SHA-256")
    f.inputStream().use { s -> val b = ByteArray(1 shl 16); while (true) { val n = s.read(b); if (n < 0) break; md.update(b, 0, n) } }
    return md.digest().joinToString("") { "%02x".format(it) }
}

allprojects {
    tasks.register("dumpDeps") {
        notCompatibleWithConfigurationCache("prototype")
        doLast {
            val result = linkedMapOf<String, Any>()
            for (confName in listOf("compileClasspath", "runtimeClasspath", "annotationProcessor", "testRuntimeClasspath")) {
                val conf = configurations.findByName(confName) ?: continue
                if (!conf.isCanBeResolved) continue
                val incoming = conf.incoming
                val rr = incoming.resolutionResult
                val nodes = linkedMapOf<String, Any>()
                val edges = mutableListOf<Map<String, String>>()
                for (c in rr.allComponents) {
                    val id = c.id
                    val key = id.displayName
                    val node = linkedMapOf<String, Any?>()
                    when (id) {
                        is ModuleComponentIdentifier -> { node["kind"] = "module"; node["group"] = id.group; node["module"] = id.module; node["version"] = id.version; node["timestamp"] = runCatching { id.javaClass.getMethod("getTimestamp").invoke(id) as String? }.getOrNull() }
                        is ProjectComponentIdentifier -> { node["kind"] = "project"; node["path"] = id.projectPath }
                        else -> node["kind"] = id.javaClass.simpleName
                    }
                    node["variant"] = c.variants.map { it.displayName }
                    node["repository"] = runCatching { c.javaClass.getMethod("getRepositoryName").invoke(c) as String? }.getOrNull()
                    nodes[key] = node
                    for (d in c.dependencies) {
                        if (d is ResolvedDependencyResult) {
                            edges += mapOf("from" to key, "to" to d.selected.id.displayName, "requested" to d.requested.displayName, "constraint" to d.isConstraint.toString())
                        } else {
                            edges += mapOf("from" to key, "to" to "UNRESOLVED:" + d.requested.displayName)
                        }
                    }
                }
                val artifacts = incoming.artifacts.resolvedArtifacts.get().map { a ->
                    linkedMapOf(
                        "component" to a.id.componentIdentifier.displayName,
                        "file" to a.file.name,
                        "path" to a.file.absolutePath,
                        "sha256" to (if (a.file.isFile) sha256(a.file) else null),
                        "attributes" to a.variant.attributes.keySet().associate { k -> k.name to a.variant.attributes.getAttribute(k).toString() },
                    )
                }
                result[confName] = mapOf("nodes" to nodes, "edges" to edges, "artifacts" to artifacts)
            }
            val outFile = rootProject.layout.buildDirectory.dir("deps").get().asFile.also { it.mkdirs() }.resolve(project.name + ".json")
            outFile.writeText(groovy.json.JsonOutput.prettyPrint(groovy.json.JsonOutput.toJson(result)))
            println("wrote " + outFile + " (" + outFile.length() + " bytes)")
        }
    }
}
