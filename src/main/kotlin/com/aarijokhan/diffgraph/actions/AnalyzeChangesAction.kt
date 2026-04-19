package com.aarijokhan.diffgraph.actions

import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.Messages
import com.intellij.openapi.vcs.changes.ChangeListManager

class AnalyzeChangesAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return

        val changeListManager = ChangeListManager.getInstance(project)
        val changes = changeListManager.allChanges

        if (changes.isEmpty()) {
            Messages.showInfoMessage(
                project,
                "No changes detected",
                "DiffGraph: Analyze Changes"
            )
            return
        }

        val changeDetails = StringBuilder()
        for (change in changes) {
            val changeType = change.type.toString()
            val filePath = change.virtualFile?.path ?: "Unknown"
            changeDetails.append("$changeType: $filePath\n")
        }

        Messages.showInfoMessage(
            project,
            changeDetails.toString().trimEnd(),
            "DiffGraph: Analyze Changes"
        )
    }
}
