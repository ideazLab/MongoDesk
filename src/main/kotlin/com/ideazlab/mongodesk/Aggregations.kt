package com.ideazlab.mongodesk

import org.bson.BsonArray
import org.bson.BsonDocument
import org.bson.BsonValue
import org.bson.json.JsonMode
import org.bson.json.JsonWriterSettings
import java.util.UUID

/** Stage bodies remain text while editing, including temporarily invalid JSON. */
data class PipelineStage(val operator: String, val body: String, val enabled: Boolean = true, val id: String = UUID.randomUUID().toString())

val stageTemplates = linkedMapOf(
    "\$match" to "{}",
    "\$group" to "{\"_id\": null, \"count\": {\"\$sum\": 1}}",
    "\$project" to "{\"_id\": 1}",
    "\$sort" to "{\"_id\": 1}",
    "\$limit" to "100",
    "\$skip" to "0",
    "\$unwind" to "\"\$items\"",
    "\$lookup" to "{\"from\": \"other_collection\", \"localField\": \"foreignId\", \"foreignField\": \"_id\", \"as\": \"joined\"}",
    "\$count" to "\"count\"",
    "\$addFields" to "{\"newField\": \"\$existingField\"}",
    "\$unset" to "\"field\"",
    "\$facet" to "{\"results\": []}"
)

fun validatePipeline(stages: List<BsonDocument>): List<BsonDocument> {
    stages.forEachIndexed { index, stage ->
        if (stage.size != 1 || !stage.firstKey.startsWith("\$"))
            throw InputError("Stage ${index + 1} must contain exactly one aggregation operator, such as \$match.")
        if (stage.firstKey in setOf("\$out", "\$merge"))
            throw InputError("Stage ${index + 1}: ${stage.firstKey} writes to the database. This aggregation workspace supports read-only pipelines.")
    }
    return stages
}

fun parsePipeline(json: String): List<BsonDocument> {
    val array = try { BsonArray.parse(json) } catch (_: Exception) {
        throw InputError("Enter a valid Extended JSON array of stages, for example [{\"\$match\":{}}].")
    }
    return validatePipeline(array.values.mapIndexed { index, value ->
        if (!value.isDocument) throw InputError("Stage ${index + 1} must be a JSON object.")
        value.asDocument()
    })
}

fun compileStages(stages: List<PipelineStage>): List<BsonDocument> = validatePipeline(stages.mapIndexedNotNull { index, stage ->
    if (!stage.enabled) return@mapIndexedNotNull null
    if (!stage.operator.matches(Regex("\\$[A-Za-z][A-Za-z0-9]*"))) throw InputError("Stage ${index + 1}: enter an operator beginning with \$.")
    val wrapped = try { BsonDocument.parse("{\"value\":${stage.body}}") } catch (_: Exception) {
        throw InputError("Stage ${index + 1} (${stage.operator}) has invalid Extended JSON.")
    }
    if (wrapped.size != 1) throw InputError("Stage ${index + 1} must contain one JSON value.")
    BsonDocument(stage.operator, wrapped["value"]!!)
})

private val pipelineJson = JsonWriterSettings.builder().indent(true).outputMode(JsonMode.EXTENDED).build()
fun pipelineToJson(stages: List<BsonDocument>): String = BsonDocument("pipeline", BsonArray(stages)).toJson(pipelineJson)
    .let { it.substring(it.indexOf('['), it.lastIndexOf(']') + 1) }
fun stageBodyJson(value: BsonValue): String = BsonDocument("value", value).toJson(pipelineJson)
    .let { it.substring(it.indexOf(':') + 1, it.lastIndexOf('}')).trim() }
fun stagesFromPipeline(json: String): List<PipelineStage> = parsePipeline(json).map { PipelineStage(it.firstKey, stageBodyJson(it[it.firstKey]!!)) }
class AggregationRunError(cause: Throwable) : Exception("Aggregation failed", cause)
