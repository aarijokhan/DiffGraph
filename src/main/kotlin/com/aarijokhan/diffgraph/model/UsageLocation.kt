package com.aarijokhan.diffgraph.model

data class UsageLocation(
    val filePath: String,
    val line: Int,
    val isInChangedFile: Boolean
)
