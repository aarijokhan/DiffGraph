package com.aarijokhan.diffgraph.model

data class DependencyEdge(
    val upstream: ChangedSymbol,
    val downstream: ChangedSymbol
)
