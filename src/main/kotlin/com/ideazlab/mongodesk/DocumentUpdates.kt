package com.ideazlab.mongodesk

import org.bson.BsonDocument
import org.bson.BsonArray
import org.bson.BsonString

fun parseEditedDocument(original: BsonDocument, json: String): BsonDocument {
    val replacement = try { BsonDocument.parse(json) } catch (_: Exception) { throw InputError("Enter one valid Extended JSON document.") }
    if (!original.containsKey("_id")) throw InputError("This document has no _id and cannot be edited here.")
    if (!replacement.containsKey("_id") || replacement["_id"] != original["_id"])
        throw InputError("Keep the original _id value and BSON type unchanged.")
    if (replacement.keys.any { it.startsWith("\$") }) throw InputError("Enter a complete document, not update operators such as \$set.")
    return replacement
}

data class DocumentFieldChange(val field: String, val kind: String)
fun documentChanges(original: BsonDocument, replacement: BsonDocument): List<DocumentFieldChange> =
    (original.keys + replacement.keys).distinct().mapNotNull { field ->
        when {
            !original.containsKey(field) -> DocumentFieldChange(field, "Added")
            !replacement.containsKey(field) -> DocumentFieldChange(field, "Removed")
            original[field] != replacement[field] -> DocumentFieldChange(field, "Changed")
            else -> null
        }
    }

/** An atomic compare-and-replace; $$ROOT also detects newly added fields. */
fun documentSnapshotFilter(original: BsonDocument): BsonDocument {
    if (!original.containsKey("_id")) throw InputError("This document has no _id and cannot be edited here.")
    return BsonDocument("_id", BsonDocument("\$eq", original["_id"]!!))
        .append("\$expr", BsonDocument("\$eq", BsonArray(listOf(BsonString("\$\$ROOT"), BsonDocument("\$literal", original)))))
}
class DocumentConflictError : Exception("Document changed or was deleted")
class DocumentUpdateError(cause: Throwable) : Exception("Document update failed", cause)

fun parseNewDocument(json: String): BsonDocument {
    val document = try { BsonDocument.parse(json) } catch (_: Exception) { throw InputError("Enter one valid Extended JSON document.") }
    if (document.keys.any { it.startsWith("\$") }) throw InputError("Enter a document, not update operators such as \$set.")
    if (document["_id"]?.isArray == true) throw InputError("The _id value cannot be an array.")
    if (!document.containsKey("_id")) document["_id"] = org.bson.BsonObjectId(org.bson.types.ObjectId())
    return document
}
class DocumentInsertError(cause: Throwable) : Exception("Document insert failed", cause)
class DocumentDeleteError(cause: Throwable) : Exception("Document deletion failed", cause)
