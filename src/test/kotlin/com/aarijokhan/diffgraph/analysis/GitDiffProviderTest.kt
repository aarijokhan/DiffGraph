package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangeType
import com.aarijokhan.diffgraph.model.ChangedFile
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File
import java.nio.file.Files
import java.nio.file.Path

class GitDiffProviderTest {

    private lateinit var repoRoot: Path
    private val provider = GitDiffProvider()

    @Before
    fun setUp() {
        repoRoot = Files.createTempDirectory("diffgraph-git-test-")
        runGit("init")
        runGit("config", "user.email", "test@diffgraph.local")
        runGit("config", "user.name", "DiffGraph Test")
        runGit("config", "commit.gpgsign", "false")
    }

    @After
    fun tearDown() {
        repoRoot.toFile().deleteRecursively()
    }

    @Test
    fun returnsEmptyListWhenNothingStaged() {
        writeFile("README.md", "hello\n")
        runGit("add", "README.md")
        runGit("commit", "-m", "initial")

        val changes = provider.getStagedChanges(repoRoot)
        assertTrue("expected no staged changes, got $changes", changes.isEmpty())
    }

    @Test
    fun returnsSingleModifiedFileForStagedEdit() {
        writeFile("Foo.kt", "class Foo { fun a() {} }\n")
        runGit("add", "Foo.kt")
        runGit("commit", "-m", "initial")

        writeFile("Foo.kt", "class Foo { fun a() { println(\"hi\") } }\n")
        runGit("add", "Foo.kt")

        val changes = provider.getStagedChanges(repoRoot)
        assertEquals(1, changes.size)
        val change = changes.single()
        assertEquals("Foo.kt", change.projectRelativePath)
        assertEquals(ChangeType.MODIFIED, change.changeType)
        assertFalse(change.isBinary)
        assertTrue(change.hasPsiSupport)
        assertTrue(change.diffHunks.isNotEmpty())
    }

    @Test
    fun distinguishesStagedFromUnstagedChanges() {
        writeFile("Foo.kt", "class Foo { fun a() {} }\n")
        runGit("add", "Foo.kt")
        runGit("commit", "-m", "initial")

        writeFile("Foo.kt", "class Foo { fun a() { println(\"staged\") } }\n")
        runGit("add", "Foo.kt")
        writeFile("Foo.kt", "class Foo { fun a() { println(\"unstaged\") } }\n")

        val changes = provider.getStagedChanges(repoRoot)
        assertEquals(1, changes.size)
        val hunkText = changes.single().diffHunks.flatMap { it.addedContent }.joinToString("\n")
        assertTrue(
            "expected staged content, not unstaged; got: $hunkText",
            hunkText.contains("staged") && !hunkText.contains("unstaged")
        )
    }

    @Test
    fun marksBinaryFilesWithIsBinary() {
        val bytes = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A, 0x00, 0x01, 0x02, 0x03)
        Files.write(repoRoot.resolve("image.png"), bytes)
        runGit("add", "image.png")

        val changes = provider.getStagedChanges(repoRoot)
        assertEquals(1, changes.size)
        val change = changes.single()
        assertTrue("expected binary detected for image.png: $change", change.isBinary)
        assertFalse("binary should not have PSI support", change.hasPsiSupport)
    }

    @Test
    fun capsAtThirtyFilesAndPrioritizesModifiedOverDeleted() {
        for (i in 1..20) {
            writeFile("m$i.kt", "val x = $i\n")
        }
        for (i in 1..20) {
            writeFile("d$i.kt", "val y = $i\n")
        }
        runGit("add", ".")
        runGit("commit", "-m", "initial")

        for (i in 1..20) {
            writeFile("m$i.kt", "val x = ${i * 10}\n")
        }
        for (i in 1..20) {
            Files.delete(repoRoot.resolve("d$i.kt"))
        }
        runGit("add", "-A")

        val changes = provider.getStagedChanges(repoRoot)
        assertEquals("capped at MAX_FILES", 30, changes.size)

        val modifiedCount = changes.count { it.changeType == ChangeType.MODIFIED }
        val deletedCount = changes.count { it.changeType == ChangeType.DELETED }
        assertEquals("all 20 modified files should survive the cap", 20, modifiedCount)
        assertEquals("deleted files should be deprioritized", 10, deletedCount)
    }

    @Test
    fun parsesAddedAndRemovedLineNumbersFromUnifiedDiff() {
        writeFile(
            "Foo.kt",
            """
            |class Foo {
            |    fun a() {
            |        println("old")
            |    }
            |}
            |
            """.trimMargin()
        )
        runGit("add", "Foo.kt")
        runGit("commit", "-m", "initial")

        writeFile(
            "Foo.kt",
            """
            |class Foo {
            |    fun a() {
            |        println("new")
            |        println("extra")
            |    }
            |}
            |
            """.trimMargin()
        )
        runGit("add", "Foo.kt")

        val changes = provider.getStagedChanges(repoRoot)
        val hunks = changes.single().diffHunks
        assertTrue(hunks.isNotEmpty())
        val allAdded = hunks.flatMap { it.addedContent }.joinToString("\n")
        val allRemoved = hunks.flatMap { it.removedContent }.joinToString("\n")
        assertTrue("added content should contain new println: $allAdded", allAdded.contains("new"))
        assertTrue("added content should contain extra println: $allAdded", allAdded.contains("extra"))
        assertTrue("removed content should contain old println: $allRemoved", allRemoved.contains("old"))
        val addedLineNums = hunks.flatMap { it.addedLineNumbers }
        assertTrue("expected at least 2 added line numbers, got $addedLineNums", addedLineNums.size >= 2)
    }

    @Test
    fun detectsAddedFileWithNewFileMode() {
        writeFile("README.md", "hello\n")
        runGit("add", "README.md")
        runGit("commit", "-m", "initial")

        writeFile("New.kt", "class New {}\n")
        runGit("add", "New.kt")

        val changes = provider.getStagedChanges(repoRoot)
        val added = changes.singleOrNull { it.changeType == ChangeType.ADDED }
        assertNotNull("expected one ADDED file, got: $changes", added)
        assertEquals("New.kt", added!!.projectRelativePath)
    }

    private fun writeFile(relative: String, content: String) {
        val file = repoRoot.resolve(relative).toFile()
        file.parentFile?.mkdirs()
        file.writeText(content)
    }

    private fun runGit(vararg args: String) {
        val process = ProcessBuilder(listOf("git") + args.toList())
            .directory(repoRoot.toFile())
            .redirectErrorStream(true)
            .start()
        val output = process.inputStream.bufferedReader().readText()
        val exit = process.waitFor()
        check(exit == 0) { "git ${args.joinToString(" ")} failed: $output" }
    }
}
