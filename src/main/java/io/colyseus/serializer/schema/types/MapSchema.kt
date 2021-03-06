package io.colyseus.serializer.schema.types

import com.fasterxml.jackson.annotation.JsonIgnore
import io.colyseus.serializer.schema.IRef
import io.colyseus.serializer.schema.ISchemaCollection
import io.colyseus.serializer.schema.ReferenceTracker
import io.colyseus.serializer.schema.Schema
import io.colyseus.util.callbacks.Function2Void
import io.colyseus.util.default
import kotlin.collections.set

class MapSchema<T : Any?>(
        @JsonIgnore public var ct: Class<T>?,
) : LinkedHashMap<String, T?>(), ISchemaCollection {

    constructor() : this(null)

    @JsonIgnore
    public var onAdd: ((value: T, key: String) -> Unit)? = null

    public fun setOnAdd(f: Function2Void<T, String>) {
        onAdd = f::invoke
    }

    @JsonIgnore
    public var onChange: ((value: T, key: String) -> Unit)? = null

    public fun setOnChange(f: Function2Void<T, String>) {
        onChange = f::invoke
    }

    @JsonIgnore
    public var onRemove: ((value: T, key: String) -> Unit)? = null

    public fun setOnRemove(f: Function2Void<T, String>) {
        onRemove = f::invoke
    }

    var indexes = HashMap<Int, String>()

    public override var __refId: Int = 0
    public override var __parent: IRef? = null

    override fun getByIndex(index: Int): Any? {
        val dynamicIndex: String? = getIndex(index) as String?
        return if (dynamicIndex != null && contains(dynamicIndex)) get(dynamicIndex) else getTypeDefaultValue()
    }

    override fun deleteByIndex(index: Int) {
        val dynamicIndex: String? = getIndex(index) as String?
        //
        // FIXME:
        // The schema encoder should not encode a DELETE operation when using ADD + DELETE in the same key. (in the same patch)
        //
        if (dynamicIndex != null && contains(dynamicIndex)) {
            remove(dynamicIndex)
            indexes.remove(index)
        }
    }

    public override fun setIndex(index: Int, dynamicIndex: Any) {
        indexes[index] = dynamicIndex as String
    }

    public override fun setByIndex(index: Int, dynamicIndex: Any, value: Any?) {
        indexes[index] = dynamicIndex as String
        this[dynamicIndex] = value as T?
    }

    public override fun getIndex(index: Int): Any? {
        return indexes[index]
    }

    public override fun _clone(): ISchemaCollection {
        val clone = MapSchema(ct)
        clone.onAdd = onAdd
        clone.onAdd = onChange
        clone.onAdd = onRemove
        return clone
    }

    override fun _keys(): MutableSet<*> {
        return keys
    }

    override fun _get(index: Any?): Any? {
        return this[index]
    }

    override fun _set(index: Any?, value: Any?) {
        this[index as String] = value as T?
    }

    public override fun getChildType(): Class<*> {
        return ct!!
    }

    public override fun getTypeDefaultValue(): Any? {
        return default(ct!!::class.java)
    }

    public override fun _containsKey(key: Any): Boolean {
        return contains(key as String)
    }

    public override fun hasSchemaChild(): Boolean = (Schema::class.java).isAssignableFrom(ct)

    public override var childPrimitiveType: String? = null

    public fun _add(item: Pair<String, T>) {
        this[item.first] = item.second
    }

    public override fun _clear(refs: ReferenceTracker?) {
        if (refs != null && hasSchemaChild()) {
            for (item in values) {
                if (item == null) continue
                refs.remove((item as IRef).__refId)
            }
        }

        indexes.clear()
        clear()
    }

    public fun _contains(item: Pair<String, T>): Boolean {
        return contains(item.first)
    }

    public fun _remove(item: Pair<String, T>): Boolean {
        val value: T? = this[item.first]
        if (value != null && value.equals(item.second)) {
            remove(item.first)
            return true
        }
        return false
    }

    public fun _count(): Int {
        return size
    }

    public fun _add(key: String, value: T) {
        this[key] = value
    }

    public fun _remove(key: String): Boolean {
        val result = contains(key)
        if (result) {
            remove(key)
        }
        return result
    }


    public override fun triggerAll() {
        if (onAdd == null) return
        for (item in this) {
            if (item.value == null) continue
            onAdd?.invoke(item.value as T, item.key)
        }
    }

    public override fun moveEventHandlers(previousInstance: IRef) {
        onAdd = (previousInstance as (MapSchema<T?>)).onAdd
        onChange = (previousInstance).onChange
        onRemove = (previousInstance).onRemove
    }

    public override fun invokeOnAdd(item: Any, index: Any) {
        onAdd?.invoke(item as T, index as String)
    }

    public override fun invokeOnChange(item: Any, index: Any) {
        onChange?.invoke(item as T, index as String)
    }

    public override fun invokeOnRemove(item: Any, index: Any) {
        onRemove?.invoke(item as T, index as String)
    }
}