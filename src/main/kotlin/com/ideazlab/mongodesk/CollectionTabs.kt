package com.ideazlab.mongodesk

import androidx.compose.runtime.*
import org.bson.BsonDocument
import org.bson.Document
import java.nio.file.*
import java.util.UUID

internal data class CollectionTabRef(val id: String = UUID.randomUUID().toString(), val connectionId: String, val database: String, val collection: String, val view: DocumentView = DocumentView.TABLE) {
    fun sameCollection(other: CollectionTabRef) = connectionId == other.connectionId && database == other.database && collection == other.collection
}

internal data class SidebarExpansion(val expanded: Boolean = true, val databases: Set<String> = emptySet())

internal data class WorkspaceSnapshot(val tabs: List<CollectionTabRef>, val openConnectionIds: List<String>, val activeTabId: String? = null, val sidebar: Map<String, SidebarExpansion> = emptyMap())

/** Only collection locations are persisted; view preferences are included, while credentials and database results never enter this index. */
internal class CollectionTabStore(private val directory: Path = Path.of(System.getProperty("user.home"), ".mongodesk")) {
    private val file get() = directory.resolve("workspace.json")
    fun read(): List<CollectionTabRef> = readWorkspace().tabs
    fun readWorkspace(): WorkspaceSnapshot {
        if (!Files.exists(file)) return WorkspaceSnapshot(emptyList(), emptyList())
        try {
            val root = Document.parse(Files.readString(file))
            require(root.getInteger("version") == 1)
            val tabs = root.getList("tabs", Document::class.java).map {
                CollectionTabRef(it.getString("id"), it.getString("connectionId"), it.getString("database"), it.getString("collection"),
                    DocumentView.entries.firstOrNull { view -> view.name == it["view"] } ?: DocumentView.TABLE).also { tab ->
                    require(tab.id.isNotBlank() && tab.connectionId.isNotBlank() && tab.database.isNotBlank() && tab.collection.isNotBlank())
                }
            }
            require(tabs.map { it.id }.distinct().size == tabs.size)
            val ids = if (root.containsKey("openConnectionIds")) root.getList("openConnectionIds", String::class.java).also { values -> require(values.all { it.isNotBlank() }) }
                else tabs.map { it.connectionId } // Upgrade the original tab-only workspace.
            val unique = tabs.distinctBy { Triple(it.connectionId, it.database, it.collection) }
            val active = (root["activeTabId"] as? String)?.takeIf { id -> unique.any { it.id == id } }
            val sidebar = if (!root.containsKey("sidebar")) emptyMap() else root.getList("sidebar", Document::class.java).associate { item ->
                val id = item.getString("connectionId"); require(id.isNotBlank())
                id to SidebarExpansion(item.getBoolean("expanded"), item.getList("databases", String::class.java).toSet())
            }
            return WorkspaceSnapshot(unique, ids.distinct(), active, sidebar)
        } catch (_: Exception) { throw InputError("Could not restore collection tabs. Back up and check .mongodesk/workspace.json; it has not been overwritten.") }
    }
    fun save(tabs: List<CollectionTabRef>, openConnectionIds: List<String> = readWorkspace().openConnectionIds, activeTabId: String? = readWorkspace().activeTabId, sidebar: Map<String, SidebarExpansion> = readWorkspace().sidebar) {
        Files.createDirectories(directory)
        val temp = Files.createTempFile(directory, "workspace-", ".tmp")
        try {
            val json = Document("version", 1).append("tabs", tabs.map {
                Document("id", it.id).append("connectionId", it.connectionId).append("database", it.database).append("collection", it.collection).append("view", it.view.name)
            }).append("openConnectionIds", openConnectionIds.distinct()).append("activeTabId", activeTabId?.takeIf { id -> tabs.any { it.id == id } }).append("sidebar", sidebar.map { (id, state) ->
                Document("connectionId", id).append("expanded", state.expanded).append("databases", state.databases.toList())
            }).toJson()
            Files.writeString(temp, json)
            try { Files.move(temp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING) }
            catch (_: AtomicMoveNotSupportedException) { Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING) }
        } finally { Files.deleteIfExists(temp) }
    }
}

internal class CollectionTabState(val ref: CollectionTabRef) {
    val mode = mutableStateOf("Documents")
    val documents = mutableStateOf(emptyList<BsonDocument>())
    val selected = mutableStateOf<Int?>(null)
    val filter = mutableStateOf("{}")
    val appliedFilter = mutableStateOf("{}")
    val page = mutableStateOf(0)
    val pageSize = mutableStateOf(MongoSession.PAGE_SIZE)
    val documentCount = mutableStateOf<Long?>(null)
    val hasNext = mutableStateOf(false)
    val pipeline = mutableStateOf("[]")
    val stages = mutableStateOf(listOf(PipelineStage("\$match", "{}")))
    val jsonMode = mutableStateOf(false)
    val savedPipelineId = mutableStateOf<String?>(null)
    val pipelineName = mutableStateOf("Untitled pipeline")
    val appliedPipeline = mutableStateOf(emptyList<BsonDocument>())
    val resultMode = mutableStateOf("Documents")
    val loaded = mutableStateOf(false)
    val documentView = mutableStateOf(ref.view)
    val expandedTree = mutableStateMapOf<TreePath, Boolean>()
    val selectedTreePath = mutableStateOf<TreePath?>(null)
    fun resetTree() {
        expandedTree.clear(); selectedTreePath.value = null
        if (documents.value.isNotEmpty()) expandedTree[TreePath(0)] = true
    }
    fun invalidateResults() {
        documentCount.value = null
        documents.value = emptyList(); selected.value = null; loaded.value = false; hasNext.value = false; page.value = 0; resetTree()
    }
}

internal class OpenConnection(val workspace: ConnectedWorkspace, restored: SidebarExpansion? = null) {
    val session get() = workspace.session
    var databases by mutableStateOf(workspace.databases)
    var expanded by mutableStateOf(true)
    val expandedDatabases = mutableStateMapOf<String, Boolean>()
    val collections = mutableStateMapOf<String, List<String>>()
    init {
        val db = session.preferredDatabase
        if (db.isNotBlank()) { expandedDatabases[db] = true; collections[db] = workspace.initialCollections.orEmpty() }
        if (restored != null) {
            expanded = restored.expanded; expandedDatabases.clear()
            restored.databases.filter { it in workspace.databases }.forEach { expandedDatabases[it] = true }
        }
    }
    fun sidebarState() = SidebarExpansion(expanded, expandedDatabases.filterValues { it }.keys.toSet())
}
