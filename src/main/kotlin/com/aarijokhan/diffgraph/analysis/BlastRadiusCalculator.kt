package com.aarijokhan.diffgraph.analysis

import com.aarijokhan.diffgraph.model.BlastRadiusEntry
import com.aarijokhan.diffgraph.model.ChangedSymbol
import com.aarijokhan.diffgraph.model.UsageLocation
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.project.Project

class BlastRadiusCalculator {

    fun calculate(
        project: Project,
        symbols: List<ChangedSymbol>,
        changedFilePaths: Set<String>
    ): List<BlastRadiusEntry> {
        val index = SymbolUsageIndex(project, symbols, changedFilePaths)
        return ApplicationManager.getApplication().runReadAction<List<BlastRadiusEntry>> {
            buildFromIndex(index)
        }
    }

    internal fun buildFromIndex(index: SymbolUsageIndex): List<BlastRadiusEntry> {
        val entries = index.symbols.map { symbol ->
            val locations = index.referencesOf(symbol).mapNotNull { index.usageLocationOf(it) }
            val changedCount = locations.count { it.isInChangedFile }
            val unchangedCount = locations.size - changedCount
            val sortedLocations = locations.sortedWith(LOCATION_COMPARATOR).take(LOCATIONS_CAP)
            BlastRadiusEntry(
                symbol = symbol,
                usagesInChangedFiles = changedCount,
                usagesInUnchangedFiles = unchangedCount,
                totalUsages = locations.size,
                locations = sortedLocations
            )
        }
        return entries.sortedWith(ENTRY_COMPARATOR)
    }

    companion object {
        const val LOCATIONS_CAP = 50

        private val LOCATION_COMPARATOR: Comparator<UsageLocation> =
            compareByDescending<UsageLocation> { it.isInChangedFile }
                .thenBy { it.filePath }
                .thenBy { it.line }

        private val ENTRY_COMPARATOR: Comparator<BlastRadiusEntry> =
            compareByDescending<BlastRadiusEntry> { it.totalUsages }
                .thenByDescending { it.usagesInUnchangedFiles }
                .thenBy { it.symbol.name }
    }
}
