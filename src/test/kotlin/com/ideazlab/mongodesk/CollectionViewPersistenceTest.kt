package com.ideazlab.mongodesk

import java.nio.file.Files
import kotlin.test.*

class CollectionViewPersistenceTest {
    @Test fun eachTabRestoresItsOwnViewIncludingSameCollectionOnDifferentServers() {
        val dir = Files.createTempDirectory("mongodesk-views-test")
        try {
            val refs = DocumentView.entries.mapIndexed { index, view -> CollectionTabRef(connectionId = "server-$index", database = "db", collection = "items", view = view) }
            CollectionTabStore(dir).save(refs, refs.map { it.connectionId })
            val restored = CollectionTabStore(dir).readWorkspace()
            assertEquals(refs, restored.tabs)
            assertEquals(DocumentView.entries, restored.tabs.map { CollectionTabState(it).documentView.value })
            assertEquals(refs.map { it.connectionId }, restored.openConnectionIds)
            val changed = restored.tabs.mapIndexed { index, ref -> if (index == 0) ref.copy(view = DocumentView.TREE) else ref }
            CollectionTabStore(dir).save(changed)
            assertEquals(changed, CollectionTabStore(dir).read())
        } finally { dir.toFile().deleteRecursively() }
    }
    @Test fun oldWorkspacesAndUnknownViewNamesDefaultToTable() {
        val dir = Files.createTempDirectory("mongodesk-views-test")
        try {
            for (extra in listOf("", ",\"view\":\"FUTURE_VIEW\"")) {
                Files.writeString(dir.resolve("workspace.json"), """{"version":1,"tabs":[{"id":"tab","connectionId":"a","database":"db","collection":"items"$extra}]}""")
                assertEquals(DocumentView.TABLE, CollectionTabStore(dir).read().single().view)
            }
        } finally { dir.toFile().deleteRecursively() }
    }
}
