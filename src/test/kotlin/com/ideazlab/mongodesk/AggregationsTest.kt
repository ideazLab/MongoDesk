package com.ideazlab.mongodesk

import de.bwaldvogel.mongo.MongoServer
import de.bwaldvogel.mongo.backend.memory.MemoryBackend
import kotlinx.coroutines.runBlocking
import org.bson.*
import kotlin.test.*

class AggregationsTest {
    @Test fun stageBuilderCompilesEnabledStagesInOrderAndPreservesBsonTypes() {
        val stages = listOf(
            PipelineStage("\$match", """{"_id":{"${'$'}oid":"507f1f77bcf86cd799439011"}}"""),
            PipelineStage("invalid", "broken JSON", enabled = false),
            PipelineStage("\$limit", "10")
        )
        val compiled = compileStages(stages)
        assertEquals(listOf("\$match", "\$limit"), compiled.map { it.firstKey })
        assertEquals(BsonType.OBJECT_ID, compiled[0]["\$match"]!!.asDocument()["_id"]!!.bsonType)
        assertEquals(compiled, compileStages(stagesFromPipeline(pipelineToJson(compiled))))
        assertEquals(listOf("\$limit", "\$match"), compileStages(stages.reversed()).map { it.firstKey })
    }
    @Test fun rejectsMalformedStagesAndWritePipelines() {
        listOf("{}", "[1]", "[{}]", "[{\"a\":1}]", "[{\"\$match\":{},\"\$sort\":{}}]", "[{\"\$out\":\"other\"}]", "[{\"\$merge\":\"other\"}]").forEach {
            assertFailsWith<InputError> { parsePipeline(it) }
        }
        assertFailsWith<InputError> { compileStages(listOf(PipelineStage("\$match", "bad"))) }
        assertFailsWith<InputError> { compileStages(listOf(PipelineStage("\$match", "{},\"injected\":1"))) }
        assertEquals(emptyList(), parsePipeline("[]"))
    }
    @Test fun runsMatchGroupProjectSortAndPaginatesPipelineOutput() = runBlocking<Unit> {
        val server = MongoServer(MemoryBackend())
        val address = server.bind()
        try {
            MongoSession.connect(ConnectionConfig(uri = "mongodb://127.0.0.1:${address.port}")).use { session ->
                session.client.getDatabase("fixture").getCollection<BsonDocument>("users").insertMany((1..105).map {
                    BsonDocument("_id", BsonInt32(it)).append("status", BsonString(if (it % 2 == 0) "active" else "inactive"))
                })
                val grouped = parsePipeline("""[
                    {"${'$'}match":{"status":"active"}},
                    {"${'$'}group":{"_id":"${'$'}status","count":{"${'$'}sum":1}}},
                    {"${'$'}project":{"_id":0,"status":"${'$'}_id","count":1}},
                    {"${'$'}sort":{"count":-1}}
                ]""")
                val result = session.aggregate("fixture", "users", grouped, 0).single()
                assertEquals("active", result["status"]!!.asString().value)
                assertEquals(52, result["count"]!!.asNumber().intValue())
                val sorted = parsePipeline("""[{"${'$'}sort":{"_id":1}}]""")
                assertEquals(51, session.aggregate("fixture", "users", sorted, 0).size)
                assertEquals(51, session.aggregate("fixture", "users", sorted, 1).first()["_id"]!!.asInt32().value)
                assertEquals(5, session.aggregate("fixture", "users", sorted, 2).size)
                assertEquals(26, session.aggregate("fixture", "users", sorted, 1, 25).size)
                assertEquals(26, session.aggregate("fixture", "users", sorted, 1, 25).first()["_id"]!!.asInt32().value)
                assertEquals(5, session.aggregate("fixture", "users", sorted, 4, 25).size)
                val limited = sorted + BsonDocument("\$limit", BsonInt32(3))
                assertEquals(3, session.aggregate("fixture", "users", limited, 0).size)
                assertTrue(session.aggregate("fixture", "users", limited, 1).isEmpty())
                assertFailsWith<InputError> { session.aggregate("fixture", "users", listOf(BsonDocument("\$out", BsonString("other"))), 0) }
            }
        } finally { server.shutdownNow() }
    }
}
