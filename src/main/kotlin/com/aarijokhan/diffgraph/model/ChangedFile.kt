package com.aarijokhan.diffgraph.model

data class ChangedFile(
    val projectRelativePath: String,
    val absolutePath: String,
    val changeType: ChangeType,
    val previousProjectRelativePath: String?,
    val diffHunks: List<DiffHunk>,
    val isBinary: Boolean,
    val hasPsiSupport: Boolean
)
