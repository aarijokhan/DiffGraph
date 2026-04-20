package com.aarijokhan.diffgraph.model

data class BlastRadiusEntry(
    val symbol: ChangedSymbol,
    val usagesInChangedFiles: Int,
    val usagesInUnchangedFiles: Int,
    val totalUsages: Int,
    val locations: List<UsageLocation>
)
