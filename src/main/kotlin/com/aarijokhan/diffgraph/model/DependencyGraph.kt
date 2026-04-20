package com.aarijokhan.diffgraph.model

data class DependencyGraph(
    val nodes: List<ChangedSymbol>,
    val edges: List<DependencyEdge>,
    val roots: List<ChangedSymbol>,
    val topologicalOrder: List<ChangedSymbol>
)
