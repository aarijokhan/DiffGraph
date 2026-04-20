package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.ChangeType
import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.aarijokhan.diffgraph.model.SymbolKind
import com.intellij.openapi.roots.ProjectRootManager
import com.intellij.openapi.vfs.VfsUtilCore
import com.intellij.openapi.vfs.VirtualFile
import com.intellij.psi.PsiDocumentManager
import com.intellij.psi.PsiElement
import com.intellij.psi.PsiFile
import com.intellij.psi.PsiNamedElement
import com.intellij.psi.util.PsiTreeUtil
import com.intellij.testFramework.fixtures.BasePlatformTestCase

class DependencyGraphBuilderTest : BasePlatformTestCase() {

    private val builder = DependencyGraphBuilder()

    fun testBuildsAToBEdgeWhenBReferencesA() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA {
                public void alpha() {}
            }
        """.trimIndent())
        val fileB = addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB {
                public void beta() {
                    new ClassA().alpha();
                }
            }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val beta = symbolFor(fileB, "beta")
        val changed = setOf(alpha.filePath, beta.filePath)

        val graph = builder.build(project, listOf(alpha, beta), changed)

        assertEquals("expected a single edge, got ${graph.edges}", 1, graph.edges.size)
        val edge = graph.edges.single()
        assertEquals("alpha", edge.upstream.name)
        assertEquals("beta", edge.downstream.name)
        assertEquals(listOf("alpha"), graph.roots.map { it.name })
        assertEquals(listOf("alpha", "beta"), graph.topologicalOrder.map { it.name })
    }

    fun testBuildsChainAToBToC() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val fileB = addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB { public void beta() { new ClassA().alpha(); } }
        """.trimIndent())
        val fileC = addJava("pkg/ClassC.java", """
            package pkg;
            public class ClassC { public void gamma() { new ClassB().beta(); } }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val beta = symbolFor(fileB, "beta")
        val gamma = symbolFor(fileC, "gamma")
        val changed = setOf(alpha.filePath, beta.filePath, gamma.filePath)

        val graph = builder.build(project, listOf(alpha, beta, gamma), changed)
        val edges = graph.edges.map { it.upstream.name to it.downstream.name }.toSet()
        assertTrue("alpha→beta edge missing: $edges", "alpha" to "beta" in edges)
        assertTrue("beta→gamma edge missing: $edges", "beta" to "gamma" in edges)
        assertEquals(listOf("alpha"), graph.roots.map { it.name })
        assertEquals(listOf("alpha", "beta", "gamma"), graph.topologicalOrder.map { it.name })
    }

    fun testReturnsIndependentNodesWhenNoReferences() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val fileB = addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB { public void beta() {} }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val beta = symbolFor(fileB, "beta")
        val graph = builder.build(project, listOf(alpha, beta), setOf(alpha.filePath, beta.filePath))

        assertTrue("expected no edges, got ${graph.edges}", graph.edges.isEmpty())
        assertEquals(2, graph.roots.size)
    }

    fun testIgnoresReferencesOutsideChangedFileSet() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB { public void beta() { new ClassA().alpha(); } }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val graph = builder.build(project, listOf(alpha), setOf(alpha.filePath))

        assertTrue(
            "downstream not in ChangedSymbol list: edges should be empty, got ${graph.edges}",
            graph.edges.isEmpty()
        )
        assertEquals(listOf(alpha), graph.nodes)
    }

    fun testDedupesMultipleReferencesFromSameDownstream() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val fileB = addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB {
                public void beta() {
                    new ClassA().alpha();
                    new ClassA().alpha();
                    new ClassA().alpha();
                }
            }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val beta = symbolFor(fileB, "beta")
        val graph = builder.build(project, listOf(alpha, beta), setOf(alpha.filePath, beta.filePath))

        assertEquals("three references should collapse to one edge", 1, graph.edges.size)
    }

    fun testProducesStableTopologicalOrderForTies() {
        val fileA = addJava("pkg/ClassA.java", """
            package pkg;
            public class ClassA { public void alpha() {} }
        """.trimIndent())
        val fileB = addJava("pkg/ClassB.java", """
            package pkg;
            public class ClassB { public void beta() {} }
        """.trimIndent())

        val alpha = symbolFor(fileA, "alpha")
        val beta = symbolFor(fileB, "beta")

        val graph1 = builder.build(project, listOf(beta, alpha), setOf(alpha.filePath, beta.filePath))
        val graph2 = builder.build(project, listOf(alpha, beta), setOf(alpha.filePath, beta.filePath))
        assertEquals(graph1.topologicalOrder.map { it.name }, graph2.topologicalOrder.map { it.name })
        assertEquals(listOf("alpha", "beta"), graph1.topologicalOrder.map { it.name })
    }

    private fun addJava(path: String, text: String): PsiFile =
        myFixture.addFileToProject(path, text)

    private fun symbolFor(psiFile: PsiFile, name: String): ChangedSymbol {
        val doc = PsiDocumentManager.getInstance(project).getDocument(psiFile)!!
        val element = PsiTreeUtil.findChildrenOfType(psiFile, PsiNamedElement::class.java)
            .first { it.name == name }
        val startOffset = (element as PsiElement).textRange.startOffset
        val startLine = doc.getLineNumber(startOffset) + 1
        return ChangedSymbol(
            name = name,
            qualifiedName = null,
            kind = SymbolKind.METHOD,
            filePath = relativePathOf(psiFile.virtualFile),
            changeType = ChangeType.MODIFIED,
            startLineInNewFile = startLine,
            signatureHint = name
        )
    }

    private fun relativePathOf(vFile: VirtualFile): String {
        val base = project.basePath
        if (base != null && vFile.path.startsWith(base)) {
            val rel = vFile.path.removePrefix(base).trimStart('/')
            if (rel.isNotEmpty()) return rel
        }
        val contentRoots = ProjectRootManager.getInstance(project).contentRoots
        for (root in contentRoots) {
            VfsUtilCore.getRelativePath(vFile, root)?.let { return it }
        }
        return vFile.path
    }
}
