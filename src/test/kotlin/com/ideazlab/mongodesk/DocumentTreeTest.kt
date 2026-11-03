package com.ideazlab.mongodesk

import org.bson.*
import kotlin.test.*

class DocumentTreeTest {
    private val documents = listOf(BsonDocument.parse("""{"_id":1,"profile":{"name":"Ada","roles":["read",{"scope":true}]},"empty":[],"nil":null,"a.b":{"c/d":2},"a":{"b":3}}"""), BsonDocument.parse("{_id:2,name:'Other'}"))
    @Test fun collapsedRootsDoNotFlattenTheirChildren() {
        val rows = documentTreeRows(documents, emptySet())
        assertEquals(2, rows.size)
        assertTrue(rows.all { it.root })
        assertEquals("Document", rows.first().type)
        assertEquals("{ 6 fields }", rows.first().summary)
    }
    @Test fun nestedObjectsAndArrayElementsUseCorrectDocumentAndDepth() {
        val rows = documentTreeRows(documents, setOf(TreePath(0), TreePath(0, listOf("profile")), TreePath(0, listOf("profile", "roles")), TreePath(0, listOf("profile", "roles", "1"))))
        val scope = rows.single { it.key == "scope" }
        assertEquals(TreePath(0, listOf("profile", "roles", "1", "scope")), scope.path)
        assertEquals(4, scope.depth); assertEquals("Bool", scope.type); assertEquals("true", scope.summary)
        assertEquals("[ 2 items ]", rows.single { it.key == "roles" }.summary)
        assertEquals("String", rows.single { it.key == "name" }.type)
        assertEquals(1, rows.last().path.document)
    }
    @Test fun unusualFieldNamesAndEmptyValuesStayDistinct() {
        val paths = setOf(TreePath(0), TreePath(0, listOf("a.b")), TreePath(0, listOf("a")))
        val rows = documentTreeRows(documents, paths)
        assertNotEquals(rows.single { it.key == "c/d" }.path, rows.single { it.key == "b" }.path)
        assertFalse(rows.single { it.key == "empty" }.expandable)
        assertEquals("[ 0 items ]", rows.single { it.key == "empty" }.summary)
        assertEquals("null", rows.single { it.key == "nil" }.summary)
        assertEquals("Null", rows.single { it.key == "nil" }.type)
    }
    @Test fun treeStateIsIndependentBetweenTabsAndResetsWithResults() {
        val a = CollectionTabState(CollectionTabRef(connectionId = "a", database = "db", collection = "items"))
        val b = CollectionTabState(CollectionTabRef(connectionId = "b", database = "db", collection = "items"))
        a.documentView.value = DocumentView.TREE; a.documents.value = documents; a.resetTree()
        a.expandedTree[TreePath(0, listOf("profile"))] = true
        assertEquals(DocumentView.TABLE, b.documentView.value); assertTrue(b.expandedTree.isEmpty())
        a.invalidateResults(); assertTrue(a.expandedTree.isEmpty())
        assertEquals(DocumentView.TREE, a.documentView.value)
    }
}
