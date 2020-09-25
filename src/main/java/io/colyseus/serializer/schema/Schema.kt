package io.colyseus.serializer.schema

import com.fasterxml.jackson.annotation.JsonIgnore
import io.colyseus.getType
import io.colyseus.isPrimary
import io.colyseus.serializer.schema.SPEC.SWITCH_TO_STRUCTURE
import io.colyseus.serializer.schema.types.ArraySchema
import io.colyseus.serializer.schema.types.MapSchema
import io.colyseus.serializer.schema.types.Reflection
import kotlin.collections.HashMap
import kotlin.collections.List
import kotlin.collections.MutableSet
import kotlin.collections.arrayListOf
import kotlin.collections.contains
import kotlin.collections.get
import kotlin.collections.hashMapOf
import kotlin.collections.set


/*
   Allowed primitive types:
       "string"
       "number"
       "boolean"
       "int8"
       "uint8"
       "int16"
       "uint16"
       "int32"
       "uint32"
       "int64"
       "uint64"
       "float32"
       "float64"
       Allowed reference types:
       "ref"
       "array"
       "map"
*/

public class Iterator(public var offset: Int = 0)

public enum class SPEC(val value: Int) {
    SWITCH_TO_STRUCTURE(255),
    TYPE_ID(213)
}

public enum class OPERATION(val value: Int) {
    ADD(128),
    REPLACE(0),
    DELETE(64),
    DELETE_AND_ADD(192),
    CLEAR(10),
}

class DataChange(
        var op: Int = 0,
        var field: String? = null,
        var dynamicIndex: Any? = null,
        var value: Any? = null,
        var previousValue: Any? = null,
)

public interface ISchemaCollection {
    fun moveEventHandlers(previousInstance: ISchemaCollection)
    fun invokeOnAdd(item: Any, index: Any)
    fun invokeOnChange(item: Any, index: Any)
    fun invokeOnRemove(item: Any, index: Any)

    fun triggerAll()
    fun _clear(refs: ReferenceTracker?)

    fun getChildType(): Class<*>?
    fun getTypeDefaultValue(): Any?

    fun _containsKey(key: Any): Boolean

    fun hasSchemaChild(): Boolean
    var childPrimitiveType: String?


    fun setIndex(index: Int, dynamicIndex: Any)
    fun getIndex(index: Int): Any?
    fun setByIndex(index: Int, dynamicIndex: Any, value: Any?)

    fun _clone(): ISchemaCollection

    fun _keys(): MutableSet<*>
    fun _get(index: Any?): Any?
    fun _set(index: Any?, value: Any?)
}


public interface IRef {
    public var __refId: Int
    var __parent: IRef?
    var referencedType: Short

    fun getByIndex(index: Int): Any?

    fun deleteByIndex(index: Int)
}


open class Schema(final override var referencedType: Short = (-1).toShort()) : HashMap<String, Any?>(), IRef {

    var fieldsByIndex = HashMap<Int, String?>()
    var fieldTypes = HashMap<String, Class<*>?>()
    var fieldTypeNames = HashMap<String, String?>()
    var fieldChildPrimitiveTypes = HashMap<String, String?>()
    var fieldChildTypes = HashMap<String, Class<*>?>()

    public override fun clone(): Schema {
        return Schema().also {
            it.putAll(this)
            it.fieldsByIndex.putAll(fieldsByIndex)
            it.fieldTypes.putAll(fieldTypes)
            it.fieldTypeNames.putAll(fieldTypeNames)
            it.fieldChildPrimitiveTypes.putAll(fieldChildPrimitiveTypes)
            it.fieldChildTypes.putAll(fieldChildTypes)
            it.onChange = onChange
            it.onRemove = onRemove
        }
    }

    @JsonIgnore
    var onChange: ((changes: List<DataChange?>) -> Unit)? = null

    @JsonIgnore
    var onRemove: (() -> Unit)? = null

    @JsonIgnore
    public override var __refId: Int = 0

    @JsonIgnore
    public override var __parent: IRef? = null

    @JsonIgnore
    private var refs: ReferenceTracker? = null

    init {
        if (referencedType >= 0) {
            val types = Reflection.reflectionObject["types"] as ArraySchema<Schema>
            for (type in types) {
                if ((type?.get("id") as Short) == referencedType) {
                    val fields = type["fields"] as ArraySchema<Schema>
                    var i = 0
                    for (field in fields) {
                        val fieldName = field?.get("name") as String
                        val fieldType = field["type"] as String
                        val rt = field["referencedType"] as Short?
                        fieldsByIndex[i] = fieldName
                        fieldTypeNames[fieldName] = fieldType
                        fieldTypes[fieldName] = getType(fieldType)
                        if (!isPrimary(fieldType) && rt != null) {
                            fieldChildPrimitiveTypes[fieldName] = "ref"
                            fieldChildTypes[fieldName] = Schema::class.java
                        }
                        i++
                    }
                    break
                }
            }
        }
    }

    public fun decode(bytes: ByteArray, it: Iterator? = Iterator(0), refs: ReferenceTracker? = null) {
        var it = it
        var refs = refs

        if (it == null) it = Iterator()
        if (refs == null) refs = ReferenceTracker()
        this.refs = refs
        val totalBytes = bytes.size

        var refId = 0
        var _ref: IRef? = this
        _ref?.referencedType = Reflection.reflectionObject["rootType"] as Short
        var changes = arrayListOf<DataChange>()
        val allChanges = hashMapOf<Any, Any>()
        refs.add(refId, this)

        while (it.offset < totalBytes) {
            val _byte = bytes[it.offset++].toInt() and 0xFF

            if (_byte == SWITCH_TO_STRUCTURE.value) {
                refId = Decoder.decodeNumber(bytes, it).toInt()
                _ref = refs[refId]

                //
                // Trying to access a reference that haven't been decoded yet.
                //
                if (_ref == null) {
                    throw Exception("refId not found: $refId")
                }

                // create empty list of changes for this refId.
                changes = arrayListOf()
                allChanges[refId] = changes
                continue
            }


            val isSchema = _ref is Schema

            val operation = if (isSchema) _byte shr 6 shl 6 and 0xFF // "compressed" index + operation
            else _byte // "uncompressed" index + operation (array/map items)

            if (operation == OPERATION.CLEAR.value) {
                (_ref as ISchemaCollection)._clear(refs)
                continue
            }

            var fieldIndex: Int
            var fieldName: String? = null
            var fieldType: String? = null

            var childType: Class<*>? = null
            var childPrimitiveType: String? = null

            if (isSchema) {
                fieldIndex = _byte % (if (operation == 0) 255 else operation) // FIXME: JS allows (0 || 255)
                fieldName = (_ref as Schema).fieldsByIndex[fieldIndex]

                fieldType = _ref.fieldTypeNames[fieldName]
                childType = _ref.fieldChildTypes[fieldName]
            } else {
                fieldName = "" // FIXME

                fieldIndex = Decoder.decodeNumber(bytes, it).toInt()
                if ((_ref as ISchemaCollection).hasSchemaChild()) {
                    fieldType = "ref"
                    childType = (_ref as ISchemaCollection).getChildType()
                } else {
                    fieldType = (_ref as ISchemaCollection).childPrimitiveType
                }
            }


            var value: Any? = null
            var previousValue: Any? = null
            var dynamicIndex: Any? = null

            if (!isSchema) {
                previousValue = _ref.getByIndex(fieldIndex)
                if ((operation and OPERATION.ADD.value) == OPERATION.ADD.value) {
                    // MapSchema dynamic index.
//                    dynamicIndex = if ((_ref as ISchemaCollection).GetItems() is HashMap)
                    dynamicIndex = if (_ref is MapSchema<*>)
                        Decoder.decodeString(bytes, it)
                    else fieldIndex

                    (_ref as ISchemaCollection).setIndex(fieldIndex, dynamicIndex)
                } else {
                    dynamicIndex = (_ref as ISchemaCollection).getIndex(fieldIndex)
                }
            } else if (fieldName != null) { // FIXME: duplicate check
                previousValue = (_ref as Schema)[fieldName]
            }


            //
            // Delete operations
            //
            if ((operation and OPERATION.DELETE.value) == OPERATION.DELETE.value) {
                if (operation != OPERATION.DELETE_AND_ADD.value) {
                    _ref.deleteByIndex(fieldIndex)
                }

                // Flag `refId` for garbage collection.
                if (previousValue != null && previousValue is IRef) {
                    refs.remove(previousValue.__refId)
                }

                value = null
            }


            if (fieldName == null) {
                //
                // keep skipping next bytes until reaches a known structure
                // by local decoder.
                //
                val nextIterator = Iterator(offset = it.offset)

                while (it.offset < totalBytes) {
                    if (Decoder.switchStructureCheck(bytes, it)) {
                        nextIterator.offset = it.offset + 1
                        if (refs.has(Decoder.decodeNumber(bytes, nextIterator).toInt())) {
                            break
                        }
                    }

                    it.offset++
                }

                continue

            } else if (operation == OPERATION.DELETE.value) {
                //
                // FIXME: refactor me.
                // Don't do anything.
                //
            } else if (fieldType == "ref") {
                refId = Decoder.decodeNumber(bytes, it).toInt()
                value = refs[refId]

                if (operation != OPERATION.REPLACE.value) {
//                    val concreteChildType = getSchemaType(bytes, it, childType)

                    if (value == null) {
//                        value = createTypeInstance(concreteChildType)
                        val rt = when(fieldName) {
                            "" -> _ref.referencedType
                            else -> Reflection.findRt(_ref.referencedType, fieldName)
                        }
                        value = Schema(rt)

                        if (previousValue != null) {
                            value.onChange = (previousValue as Schema).onChange
                            value.onRemove = previousValue.onRemove

                            if ((previousValue as IRef).__refId > 0 && refId != previousValue.__refId) {
                                refs.remove((previousValue as IRef).__refId)
                            }
                        }
                    }

                    refs.add(refId, value as IRef, (value != previousValue))
                }
            } else if (childType == null) {
                // primitive values
                value = Decoder.decodePrimitiveType(fieldType, bytes, it)
            } else {
                refId = Decoder.decodeNumber(bytes, it).toInt()
//                value = refs[refId]

                val valueRef: ISchemaCollection = if (refs.has(refId))
                    previousValue as ISchemaCollection
                else {
                    val rt = Reflection.findRt(_ref.referencedType, fieldName)
                    if (rt == (-1).toShort()) throw Exception("Reflection Error")
                    when (fieldType) {
                        "array" -> ArraySchema(childType, rt)
                        "map" -> MapSchema(childType, rt)
                        else -> throw Error("$fieldType is not supported")
                    }
                }

//                value = valueRef._clone()
                value = valueRef

                // keep reference to nested childPrimitiveType.
                childPrimitiveType = (_ref as Schema).fieldChildPrimitiveTypes[fieldName]
                value.childPrimitiveType = childPrimitiveType!!

                if (previousValue != null) {
                    value.moveEventHandlers(previousValue as ISchemaCollection)

                    if ((previousValue as IRef).__refId > 0 && refId != (previousValue as IRef).__refId) {
                        refs.remove((previousValue as IRef).__refId)

                        val deletes = arrayListOf<DataChange>()
                        val keys = (previousValue as ISchemaCollection)._keys()

                        for (key in keys) {
                            deletes.add(DataChange(
                                    dynamicIndex = key,
                                    op = OPERATION.DELETE.value,
                                    value = null,
                                    previousValue = previousValue._get(key)
                            ))
                        }

                        allChanges[(previousValue as IRef).__refId] = deletes
                    }
                }

                refs.add(refId, value as IRef, (valueRef !== previousValue))
            }


            val hasChange = previousValue !== value

            if (value != null) {
                if (value is IRef) {
                    value.__refId = refId
                    value.__parent = _ref
                }

                if (_ref is Schema) {
                    _ref[fieldName] = value
                } else if (_ref is ISchemaCollection) {
                    (_ref as ISchemaCollection).setByIndex(fieldIndex, dynamicIndex!!, value)
                }
            }

            if (hasChange) {
                changes.add(DataChange(
                        op = operation,
                        field = fieldName,
                        dynamicIndex = dynamicIndex,
                        value = value,
                        previousValue = previousValue
                ))
            }
        }

        triggerChanges(allChanges)

        refs.garbageCollection()

    }

    public fun triggerAll() {
        val allChanges = HashMap<Any, Any>()
        triggerAllFillChanges(this, allChanges)
        triggerChanges(allChanges)
    }


    protected fun triggerAllFillChanges(currentRef: IRef, allChanges: HashMap<Any, Any>) {
        // skip recursive structures...
        if (allChanges.contains(currentRef.__refId)) {
            return
        }

        val changes = arrayListOf<DataChange>()
        allChanges[currentRef.__refId as Any] = changes

        if (currentRef is Schema) {
            for (fieldName in currentRef.fieldsByIndex.values) {
                val value = currentRef[fieldName!!]
                changes.add(DataChange(
                        field = fieldName,
                        op = OPERATION.ADD.value,
                        value = value
                ))

                if (value is IRef) {
                    triggerAllFillChanges(value, allChanges)
                }
            }
        } else {
            if ((currentRef as ISchemaCollection).hasSchemaChild()) {
                val keys = (currentRef as ISchemaCollection)._keys()
                for (key in keys) {
                    val child = currentRef._get(key)
                    changes.add(DataChange(
                            field = null,
                            dynamicIndex = key,
                            op = OPERATION.ADD.value,
                            value = child
                    ))
                    triggerAllFillChanges(child as IRef, allChanges)
                }
            }
        }
    }

    fun triggerChanges(allChanges: HashMap<Any, Any>) {
        for (key in allChanges.keys) {
            val changes = allChanges[key] as List<DataChange>?

            val _ref = refs!![key as Int]
            val isSchema = _ref is Schema

            for (change in changes!!) {
                //const listener = ref['$listeners'] && ref['$listeners'][change.field]

                if (!isSchema) {
                    val container = _ref as ISchemaCollection

                    if (change.op == OPERATION.ADD.value && change.previousValue == container.getTypeDefaultValue()) {
                        container.invokeOnAdd(change.value!!, change.dynamicIndex!!)

                    } else if (change.op == OPERATION.DELETE.value) {
                        //
                        // FIXME: `previousValue` should always be avaiiable.
                        // ADD + DELETE operations are still encoding DELETE operation.
                        //
                        if (change.previousValue != container.getTypeDefaultValue()) {
                            container.invokeOnRemove(change.previousValue!!, change.dynamicIndex
                                    ?: change.field!!)
                        }
                    } else if (change.op == OPERATION.DELETE_AND_ADD.value) {
                        if (change.previousValue != container.getTypeDefaultValue()) {
                            container.invokeOnRemove(change.previousValue!!, change.dynamicIndex!!)
                        }
                        container.invokeOnAdd(change.value!!, change.dynamicIndex!!)

                    } else if (change.op == OPERATION.REPLACE.value || change.value != change.previousValue) {
                        container.invokeOnChange(change.value!!, change.dynamicIndex!!)
                    }
                }

                //
                // trigger onRemove on child structure.
                //
                if ((change.op and OPERATION.DELETE.value) == OPERATION.DELETE.value && change.previousValue is Schema) {
                    (change.previousValue as Schema).onRemove?.invoke()
                }
            }

            if (isSchema) {
                (_ref as Schema).onChange?.invoke(changes)
            }
        }
    }

    public override fun getByIndex(index: Int): Any? {
        val fieldName: String = fieldsByIndex[index] ?: return null
        return this[fieldName]
    }

    public override fun deleteByIndex(index: Int) {
        val fieldName: String = fieldsByIndex[index] ?: return
        this[fieldName] = null
    }

}