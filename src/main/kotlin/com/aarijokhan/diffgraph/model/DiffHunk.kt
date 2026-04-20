package com.aarijokhan.diffgraph.model

data class DiffHunk(
    val oldStartLine: Int,
    val oldLineCount: Int,
    val newStartLine: Int,
    val newLineCount: Int,
    val addedLineNumbers: List<Int>,
    val removedLineNumbers: List<Int>,
    val addedContent: List<String>,
    val removedContent: List<String>
)
