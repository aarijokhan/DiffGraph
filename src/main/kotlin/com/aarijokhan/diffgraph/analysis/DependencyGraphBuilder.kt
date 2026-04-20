package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.aarijokhan.diffgraph.model.DependencyEdge
import com.aarijokhan.diffgraph.model.DependencyGraph
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project
import java.util.PriorityQueue

class DependencyGraphBuilder {

    fun build(
        project: Project,
        symbols: List<ChangedSymbol>,
        changedFilePaths: Set<String>
    ): DependencyGraph {
        val index = SymbolUsageIndex(project, symbols, changedFilePaths)
        return ApplicationManager.getApplication().runReadAction<DependencyGraph> {
            buildFromIndex(index)
        }
    }

    internal fun buildFromIndex(index: SymbolUsageIndex): DependencyGraph {
        val nodes = index.symbols
        val edges = mutableListOf<DependencyEdge>()
        val seen = HashSet<Pair<ChangedSymbol, ChangedSymbol>>()
        for (upstream in nodes) {
            for (ref in index.referencesOf(upstream)) {
                val downstream = index.enclosingChangedSymbolOf(ref) ?: continue
                if (downstream == upstream) continue
                val key = upstream to downstream
                if (seen.add(key)) {
                    edges.add(DependencyEdge(upstream, downstream))
                }
            }
        }
        val roots = computeRoots(nodes, edges)
        val topologicalOrder = topologicalSort(nodes, edges)
        return DependencyGraph(nodes, edges, roots, topologicalOrder)
    }

    private fun computeRoots(nodes: List<ChangedSymbol>, edges: List<DependencyEdge>): List<ChangedSymbol> {
        val withIncoming = edges.map { it.downstream }.toSet()
        return nodes.filterNot { it in withIncoming }
            .sortedWith(SYMBOL_COMPARATOR)
    }

    private fun topologicalSort(nodes: List<ChangedSymbol>, edges: List<DependencyEdge>): List<ChangedSymbol> {
        val inDegree = HashMap<ChangedSymbol, Int>().apply { nodes.forEach { this[it] = 0 } }
        val adjacency = HashMap<ChangedSymbol, MutableList<ChangedSymbol>>().apply {
            nodes.forEach { this[it] = mutableListOf() }
        }
        for (e in edges) {
            adjacency[e.upstream]?.add(e.downstream)
            inDegree[e.downstream] = (inDegree[e.downstream] ?: 0) + 1
        }
        val ready = PriorityQueue(SYMBOL_COMPARATOR)
        for ((n, deg) in inDegree) if (deg == 0) ready.add(n)

        val result = mutableListOf<ChangedSymbol>()
        while (ready.isNotEmpty()) {
            val n = ready.poll()
            result.add(n)
            val successors = adjacency[n].orEmpty().sortedWith(SYMBOL_COMPARATOR)
            for (next in successors) {
                val newDeg = (inDegree[next] ?: 0) - 1
                inDegree[next] = newDeg
                if (newDeg == 0) ready.add(next)
            }
        }
        if (result.size < nodes.size) {
            val included = result.toSet()
            val remaining = nodes.filterNot { it in included }
            return result + remaining
        }
        return result
    }

    companion object {
        private val SYMBOL_COMPARATOR: Comparator<ChangedSymbol> =
            compareBy({ it.filePath }, { it.name }, { it.startLineInNewFile })
    }
}
