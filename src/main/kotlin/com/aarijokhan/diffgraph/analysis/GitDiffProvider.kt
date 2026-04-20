package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangeType
import com.aarijokhan.diffgraph.model.ChangedFile
import com.aarijokhan.diffgraph.model.DiffHunk
import com.intellij.openapi.project.Project
import java.io.File
import java.nio.file.Path
import java.nio.file.Paths

class GitDiffProvider {

    companion object {
        const val MAX_FILES = 30
    }

    fun getStagedChanges(project: Project): List<ChangedFile> {
        val basePath = project.basePath ?: return emptyList()
        return getStagedChanges(Paths.get(basePath))
    }

    fun getStagedChanges(projectRoot: Path): List<ChangedFile> {
        val repoRoot = findGitRoot(projectRoot) ?: return emptyList()
        val diffOutput = runGit(repoRoot, listOf("diff", "--cached", "--no-color"))
        if (diffOutput.isBlank()) return emptyList()

        val projectRootNorm = projectRoot.toAbsolutePath().normalize()
        val parsed = parseUnifiedDiff(diffOutput)
        val changedFiles = parsed.map { parsedFile ->
            val pathForLocation = if (parsedFile.changeType == ChangeType.DELETED) {
                parsedFile.oldPath ?: parsedFile.newPath
            } else {
                parsedFile.newPath
            }
            val absolutePath = repoRoot.resolve(pathForLocation).toAbsolutePath().normalize()
            val inProject = absolutePath.startsWith(projectRootNorm)
            val projectRelativePath = if (inProject) {
                projectRootNorm.relativize(absolutePath).toString().replace(File.separatorChar, '/')
            } else {
                pathForLocation
            }
            val prevRel = if (parsedFile.changeType == ChangeType.RENAMED && parsedFile.oldPath != null) {
                val prevAbs = repoRoot.resolve(parsedFile.oldPath).toAbsolutePath().normalize()
                if (prevAbs.startsWith(projectRootNorm)) {
                    projectRootNorm.relativize(prevAbs).toString().replace(File.separatorChar, '/')
                } else {
                    parsedFile.oldPath
                }
            } else {
                null
            }
            ChangedFile(
                projectRelativePath = projectRelativePath,
                absolutePath = absolutePath.toString(),
                changeType = parsedFile.changeType,
                previousProjectRelativePath = prevRel,
                diffHunks = parsedFile.hunks,
                isBinary = parsedFile.isBinary,
                hasPsiSupport = !parsedFile.isBinary && inProject
            )
        }

        return capAndPrioritize(changedFiles)
    }

    internal fun capAndPrioritize(files: List<ChangedFile>): List<ChangedFile> {
        if (files.size <= MAX_FILES) return files
        val priority: (ChangeType) -> Int = {
            when (it) {
                ChangeType.MODIFIED -> 0
                ChangeType.ADDED -> 1
                ChangeType.RENAMED -> 2
                ChangeType.DELETED -> 3
            }
        }
        return files.sortedBy { priority(it.changeType) }.take(MAX_FILES)
    }

    private fun findGitRoot(startPath: Path): Path? {
        var current: Path? = startPath.toAbsolutePath().normalize()
        while (current != null) {
            if (current.resolve(".git").toFile().exists()) return current
            current = current.parent
        }
        return null
    }

    private fun runGit(repoRoot: Path, args: List<String>): String {
        val process = ProcessBuilder(listOf("git") + args)
            .directory(repoRoot.toFile())
            .redirectErrorStream(false)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        process.waitFor()
        return output
    }

    internal data class ParsedFile(
        val oldPath: String?,
        val newPath: String,
        val changeType: ChangeType,
        val isBinary: Boolean,
        val hunks: List<DiffHunk>
    )

    internal fun parseUnifiedDiff(diff: String): List<ParsedFile> {
        val lines = diff.split("\n")
        val result = mutableListOf<ParsedFile>()
        val headerRegex = Regex("""^diff --git a/(.+) b/(.+)$""")
        val hunkRegex = Regex("""^@@ -(\d+)(?:,(\d+))? \+(\d+)(?:,(\d+))? @@""")

        var i = 0
        while (i < lines.size) {
            if (!lines[i].startsWith("diff --git ")) {
                i++
                continue
            }
            val headerMatch = headerRegex.find(lines[i])
            val tentativeOld = headerMatch?.groupValues?.get(1)
            val tentativeNew = headerMatch?.groupValues?.get(2) ?: ""

            var renameOld: String? = null
            var renameNew: String? = null
            var changeType = ChangeType.MODIFIED
            var isBinary = false
            val hunks = mutableListOf<DiffHunk>()

            i++
            while (i < lines.size && !lines[i].startsWith("diff --git ") && !lines[i].startsWith("@@ ")) {
                val l = lines[i]
                when {
                    l.startsWith("new file mode ") -> changeType = ChangeType.ADDED
                    l.startsWith("deleted file mode ") -> changeType = ChangeType.DELETED
                    l.startsWith("rename from ") -> {
                        renameOld = l.removePrefix("rename from ")
                        changeType = ChangeType.RENAMED
                    }
                    l.startsWith("rename to ") -> {
                        renameNew = l.removePrefix("rename to ")
                        changeType = ChangeType.RENAMED
                    }
                    l.startsWith("Binary files ") -> isBinary = true
                }
                i++
            }

            while (i < lines.size && lines[i].startsWith("@@ ")) {
                val hunkMatch = hunkRegex.find(lines[i])
                if (hunkMatch == null) {
                    i++
                    continue
                }
                val oldStart = hunkMatch.groupValues[1].toInt()
                val oldCount = hunkMatch.groupValues[2].ifEmpty { "1" }.toInt()
                val newStart = hunkMatch.groupValues[3].toInt()
                val newCount = hunkMatch.groupValues[4].ifEmpty { "1" }.toInt()
                i++

                val addedLines = mutableListOf<Int>()
                val removedLines = mutableListOf<Int>()
                val addedContent = mutableListOf<String>()
                val removedContent = mutableListOf<String>()
                var curOld = oldStart
                var curNew = newStart

                while (i < lines.size) {
                    val hl = lines[i]
                    if (hl.startsWith("diff --git ") || hl.startsWith("@@ ")) break
                    when {
                        hl.startsWith("+") -> {
                            addedLines.add(curNew)
                            addedContent.add(hl.substring(1))
                            curNew++
                        }
                        hl.startsWith("-") -> {
                            removedLines.add(curOld)
                            removedContent.add(hl.substring(1))
                            curOld++
                        }
                        hl.startsWith(" ") -> {
                            curOld++
                            curNew++
                        }
                        hl.startsWith("\\") -> { /* \ No newline at end of file */ }
                        else -> break
                    }
                    i++
                }

                hunks.add(
                    DiffHunk(
                        oldStartLine = oldStart,
                        oldLineCount = oldCount,
                        newStartLine = newStart,
                        newLineCount = newCount,
                        addedLineNumbers = addedLines,
                        removedLineNumbers = removedLines,
                        addedContent = addedContent,
                        removedContent = removedContent
                    )
                )
            }

            val finalOld: String?
            val finalNew: String
            when (changeType) {
                ChangeType.RENAMED -> {
                    finalOld = renameOld ?: tentativeOld
                    finalNew = renameNew ?: tentativeNew
                }
                ChangeType.DELETED -> {
                    finalOld = tentativeOld
                    finalNew = tentativeOld ?: tentativeNew
                }
                else -> {
                    finalOld = null
                    finalNew = tentativeNew
                }
            }

            result.add(ParsedFile(finalOld, finalNew, changeType, isBinary, hunks))
        }
        return result
    }
}
