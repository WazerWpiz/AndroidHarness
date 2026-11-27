package com.androidharness.app.evolve

import com.androidharness.app.tools.EvolveDiffTool
import com.androidharness.app.tools.Tool
import com.androidharness.app.tools.EvolveHistoryTool
import com.androidharness.app.tools.EvolveStatusTool
import java.io.File

/**
 * The evolve package as the app uses it: one content-addressed store, one archive and the
 * gate in front of both. Everything here is inert until a genome is promoted through
 * [PromotionGate], which needs a person's approval.
 */
class EvolveHost(val store: GenomeStore, val archive: Archive) {

    val gate = PromotionGate(store, archive)
    val settings = EvolveSettings.DEFAULT.normalized()

    fun tools(): List<Tool> = listOf(
        EvolveStatusTool(this),
        EvolveHistoryTool(this),
        EvolveDiffTool(this),
    )

    companion object {
        fun at(root: File): EvolveHost = EvolveHost(GenomeStore(root), Archive(File(root, "archive.json")))
    }
}
