package com.aarijokhan.diffgraph.model

data class ChangedSymbol(
    val name: String,
    val qualifiedName: String?,
    val kind: SymbolKind,
    val filePath: String,
    val changeType: ChangeType,
    val startLineInNewFile: Int,
    val signatureHint: String
)
