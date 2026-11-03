package com.ideazlab.mongodesk

import org.bson.BsonType
import org.bson.BsonValue
import java.time.Instant
import java.util.Base64

/** Human-readable values for the table only; source BSON remains unchanged. */
fun BsonValue.tableValue(): String = when (bsonType) {
    BsonType.STRING -> asString().value
    BsonType.OBJECT_ID -> asObjectId().value.toHexString()
    BsonType.INT32 -> asInt32().value.toString()
    BsonType.INT64 -> asInt64().value.toString()
    BsonType.DOUBLE -> asDouble().value.toString()
    BsonType.DECIMAL128 -> asDecimal128().value.toString()
    BsonType.BOOLEAN -> asBoolean().value.toString()
    BsonType.DATE_TIME -> Instant.ofEpochMilli(asDateTime().value).toString()
    BsonType.NULL -> "null"
    BsonType.ARRAY -> asArray().values.joinToString(prefix = "[", postfix = "]") { it.tableValue() }
    BsonType.DOCUMENT -> asDocument().entries.joinToString(prefix = "{", postfix = "}") { "${it.key}: ${it.value.tableValue()}" }
    BsonType.BINARY -> Base64.getEncoder().encodeToString(asBinary().data)
    BsonType.REGULAR_EXPRESSION -> asRegularExpression().let { "/${it.pattern}/${it.options}" }
    BsonType.TIMESTAMP -> asTimestamp().let { "${Instant.ofEpochSecond(Integer.toUnsignedLong(it.time))} / ${Integer.toUnsignedLong(it.inc)}" }
    BsonType.JAVASCRIPT -> asJavaScript().code
    BsonType.JAVASCRIPT_WITH_SCOPE -> asJavaScriptWithScope().let { "${it.code} ${it.scope.tableValue()}" }
    BsonType.SYMBOL -> asSymbol().symbol
    BsonType.DB_POINTER -> asDBPointer().let { "${it.namespace} / ${it.id.toHexString()}" }
    BsonType.MIN_KEY -> "−∞"
    BsonType.MAX_KEY -> "+∞"
    BsonType.UNDEFINED -> "undefined"
    else -> "—"
}
