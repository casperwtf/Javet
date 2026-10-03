/*
 * Copyright (c) 2021-2026. caoccao.com Sam Cao
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *     http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */

package com.caoccao.javet.interop;

import com.caoccao.javet.enums.V8ValueType;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.V8FunctionCallback;
import com.caoccao.javet.utils.JavetResourceUtils;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.*;
import com.caoccao.javet.values.reference.*;

import java.lang.foreign.*;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.MethodType;
import java.lang.ref.Reference;
import java.lang.ref.WeakReference;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.function.LongBinaryOperator;

/**
 * The V8 native implementation used on Java 25+ (multi-release jar).
 * <p>
 * The hot calls cross into the native library through the Foreign Function and Memory API instead of JNI.
 * Values travel as 16-byte tagged slots in a per-thread exchange owned by the native library
 * (see cpp/jni/javet_ffm.h), so a call costs one transition and no JNI upcall per value,
 * and a JS to Java function callback costs one FFM upcall.
 * Every other method, and any call whose values cannot be expressed as slots, stays on JNI.
 * <p>
 * It keeps no per-thread Java state and its upcall stub only holds it weakly, so dropping the class loader
 * that defined it (an application that swaps its runtime) lets it be collected.
 * It must be defined by {@link JavetClassLoader}, next to {@link V8Native} and the library it loads.
 * Set the system property {@code javet.ffm} to {@code false} to stay on JNI.
 *
 * @since 6.0.1
 */
class V8NativeFfm extends V8Native implements LongBinaryOperator {
    private static final int ABI_VERSION = 3;
    private static final int SLOT_COUNT = 256;
    private static final long SLOT_SIZE = 16;
    private static final long RELEASING_HANDLE_OFFSET = 16;
    private static final long ARGC_OFFSET = 24;
    private static final long FLAGS_OFFSET = 28;
    private static final long SLOTS_OFFSET = 32;
    private static final long EXCHANGE_SIZE = SLOTS_OFFSET + SLOT_COUNT * SLOT_SIZE;
    private static final int STATUS_OK = 0;
    private static final int STATUS_THROWN = 1;
    private static final int STATUS_NULL = 2;
    private static final int TYPE_EXTERNAL = 0xFFFF;
    private static final int FLAG_ONE_BYTE = 1;
    private static final int FLAG_NEGATIVE = 1;
    private static final int CALLBACK_THIS = 1;
    private static final int CALLBACK_RESULT = 2;
    private static final int TYPE_ID_COUNT = 64;

    private final MethodHandle batchArrayGetHandle;
    private final MethodHandle batchObjectGetHandle;
    private final MethodHandle[] constructors;
    private final MethodHandle exchangeHandle;
    private final MethodHandle functionCallHandle;
    private final MethodHandle objectCreateHandle;
    private final MethodHandle objectGetHandle;
    private final MethodHandle objectInvokeHandle;
    private final MethodHandle objectSetHandle;
    private final MethodHandle objectSetPrivatePropertyHandle;
    private final MethodHandle removeReferenceHandle;
    private final MethodHandle reserveHandle;
    private final MemorySegment callbackStub;
    private volatile Map<Long, V8Runtime> v8RuntimeMap;

    V8NativeFfm() throws ReflectiveOperationException {
        super();
        Linker linker = Linker.nativeLinker();
        SymbolLookup lookup = SymbolLookup.loaderLookup();
        MethodHandle version = downcall(linker, lookup, "javet_ffm_version",
                FunctionDescriptor.of(ValueLayout.JAVA_INT), Linker.Option.critical(false));
        int abiVersion;
        try {
            abiVersion = (int) version.invokeExact();
        } catch (Throwable t) {
            throw new IllegalStateException("javet_ffm_version failed", t);
        }
        if (abiVersion != ABI_VERSION) {
            throw new IllegalStateException("Native FFM ABI " + abiVersion + " is not " + ABI_VERSION);
        }
        exchangeHandle = downcall(linker, lookup, "javet_ffm_exchange",
                FunctionDescriptor.of(ValueLayout.ADDRESS), Linker.Option.critical(false));
        reserveHandle = downcall(linker, lookup, "javet_ffm_reserve",
                FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_LONG), Linker.Option.critical(false));
        functionCallHandle = downcall(linker, lookup, "javet_ffm_function_call", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        objectCreateHandle = downcall(linker, lookup, "javet_ffm_object_create", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG));
        objectGetHandle = downcall(linker, lookup, "javet_ffm_object_get", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        objectSetHandle = downcall(linker, lookup, "javet_ffm_object_set", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        removeReferenceHandle = downcall(linker, lookup, "javet_ffm_remove_reference", FunctionDescriptor.ofVoid(
                ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        batchArrayGetHandle = downcall(linker, lookup, "javet_ffm_batch_array_get", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        batchObjectGetHandle = downcall(linker, lookup, "javet_ffm_batch_object_get", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT));
        objectInvokeHandle = downcall(linker, lookup, "javet_ffm_object_invoke", FunctionDescriptor.of(
                ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_INT,
                ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));
        objectSetPrivatePropertyHandle = downcall(linker, lookup, "javet_ffm_object_set_private_property",
                FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG));
        constructors = referenceConstructors();
        callbackStub = linker.upcallStub(weakCallback(this),
                FunctionDescriptor.of(ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG, ValueLayout.JAVA_LONG),
                Arena.ofAuto());
        MethodHandle setCallback = downcall(linker, lookup, "javet_ffm_set_callback",
                FunctionDescriptor.ofVoid(ValueLayout.ADDRESS));
        try {
            setCallback.invokeExact(callbackStub);
        } catch (Throwable t) {
            throw new IllegalStateException("javet_ffm_set_callback failed", t);
        }
    }

    private static MethodHandle downcall(
            Linker linker, SymbolLookup lookup, String name, FunctionDescriptor descriptor, Linker.Option... options) {
        MemorySegment symbol = lookup.find(name).orElseThrow(() -> new UnsatisfiedLinkError(name));
        return linker.downcallHandle(symbol, descriptor, options);
    }

    private static void putConstructor(MethodHandle[] table, V8ValueType type, Class<?> valueClass)
            throws ReflectiveOperationException {
        Constructor<?> constructor = valueClass.getDeclaredConstructor(V8Runtime.class, long.class);
        constructor.setAccessible(true);
        table[type.getId()] = MethodHandles.lookup().unreflectConstructor(constructor)
                .asType(MethodType.methodType(V8Value.class, V8Runtime.class, long.class));
    }

    private static MethodHandle[] referenceConstructors() throws ReflectiveOperationException {
        MethodHandle[] table = new MethodHandle[TYPE_ID_COUNT];
        putConstructor(table, V8ValueType.Object, V8ValueObject.class);
        putConstructor(table, V8ValueType.Error, V8ValueError.class);
        putConstructor(table, V8ValueType.RegExp, V8ValueRegExp.class);
        putConstructor(table, V8ValueType.Promise, V8ValuePromise.class);
        putConstructor(table, V8ValueType.Proxy, V8ValueProxy.class);
        putConstructor(table, V8ValueType.Symbol, V8ValueSymbol.class);
        putConstructor(table, V8ValueType.SymbolObject, V8ValueSymbolObject.class);
        putConstructor(table, V8ValueType.Arguments, V8ValueArguments.class);
        putConstructor(table, V8ValueType.Map, V8ValueMap.class);
        putConstructor(table, V8ValueType.Set, V8ValueSet.class);
        putConstructor(table, V8ValueType.Array, V8ValueArray.class);
        putConstructor(table, V8ValueType.Function, V8ValueFunction.class);
        putConstructor(table, V8ValueType.Iterator, V8ValueIterator.class);
        putConstructor(table, V8ValueType.WeakMap, V8ValueWeakMap.class);
        putConstructor(table, V8ValueType.WeakSet, V8ValueWeakSet.class);
        putConstructor(table, V8ValueType.BigIntObject, V8ValueLongObject.class);
        putConstructor(table, V8ValueType.BooleanObject, V8ValueBooleanObject.class);
        putConstructor(table, V8ValueType.DoubleObject, V8ValueDoubleObject.class);
        putConstructor(table, V8ValueType.StringObject, V8ValueStringObject.class);
        putConstructor(table, V8ValueType.DataView, V8ValueDataView.class);
        Constructor<?> typedArray = V8ValueTypedArray.class.getDeclaredConstructor(V8Runtime.class, long.class, int.class);
        typedArray.setAccessible(true);
        MethodHandle typedArrayHandle = MethodHandles.lookup().unreflectConstructor(typedArray)
                .asType(MethodType.methodType(V8Value.class, V8Runtime.class, long.class, int.class));
        for (int id = V8ValueType.Int8Array.getId(); id <= V8ValueType.BigUint64Array.getId(); ++id) {
            table[id] = MethodHandles.insertArguments(typedArrayHandle, 2, id);
        }
        return table;
    }

    @SuppressWarnings("unchecked")
    private static <T extends Throwable> RuntimeException sneakyThrow(Throwable throwable) throws T {
        // IV8Native declares no checked exceptions, but JNI throws JavetException through it anyway.
        throw (T) throwable;
    }

    /**
     * The upcall target, built from JDK classes only: weakReference.get().applyAsLong(runtime, context).
     * The stub keeps its target alive, so a direct handle to this object would pin its class loader forever.
     */
    private static MethodHandle weakCallback(V8NativeFfm target) throws ReflectiveOperationException {
        MethodHandles.Lookup lookup = MethodHandles.publicLookup();
        MethodHandle get = lookup.findVirtual(Reference.class, "get", MethodType.methodType(Object.class))
                .bindTo(new WeakReference<>(target))
                .asType(MethodType.methodType(LongBinaryOperator.class));
        MethodHandle apply = lookup.findVirtual(LongBinaryOperator.class, "applyAsLong",
                MethodType.methodType(long.class, long.class, long.class));
        return MethodHandles.collectArguments(apply, 0, get);
    }

    private native Object ffmToExternal(long v8RuntimeHandle, long referenceHandle);

    private native void ffmSetExternalException(long v8RuntimeHandle, Throwable throwable);

    private native Throwable ffmTakeThrowable();

    /**
     * JS to Java function callback, called from the native library through the FFM upcall stub.
     * The arguments are in the exchange. It must never throw: an exception escaping an upcall terminates the JVM.
     *
     * @param v8RuntimeHandle the V8 runtime handle
     * @param contextHandle   the callback context handle
     * @return the status
     */
    @Override
    public long applyAsLong(long v8RuntimeHandle, long contextHandle) {
        MemorySegment slots = exchange();
        try {
            V8Runtime v8Runtime = runtime(v8RuntimeHandle);
            JavetCallbackContext javetCallbackContext = v8Runtime.getCallbackContext(contextHandle);
            int argc = slots.get(ValueLayout.JAVA_INT, ARGC_OFFSET);
            int flags = slots.get(ValueLayout.JAVA_INT, FLAGS_OFFSET);
            V8Value thisObject = null;
            if ((flags & CALLBACK_THIS) != 0) {
                thisObject = decode(slots, v8Runtime, 0);
            }
            V8Value[] args = null;
            if (argc > 0) {
                args = new V8Value[argc];
                for (int i = 0; i < argc; ++i) {
                    args[i] = decode(slots, v8Runtime, i + 1);
                }
            }
            boolean resultRequired = (flags & CALLBACK_RESULT) != 0;
            int[] resultType = resultRequired ? new int[1] : null;
            V8Value result = V8FunctionCallback.receiveCallback(
                    v8Runtime, javetCallbackContext, thisObject, args, resultType);
            if (resultRequired && result != null) {
                encodeResult(slots, result, resultType[0]);
            }
            // A reference result is closed here, before native code reads it. Its handle was put in the
            // exchange as the releasing handle, so the close leaves the native release to the caller.
            JavetResourceUtils.safeClose(result);
            return STATUS_OK;
        } catch (Throwable throwable) {
            return callbackFailed(slots, v8RuntimeHandle, throwable);
        }
    }

    private int callbackFailed(MemorySegment slots, long v8RuntimeHandle, Throwable throwable) {
        try {
            ffmSetExternalException(v8RuntimeHandle, throwable);
            String message = throwable.getMessage();
            if (message == null) {
                putTag(slots, 0, V8ValueType.Undefined.getId(), 0, 0);
            } else {
                putString(slots, 0, message, reserve(message.length() * 2L + 2), 0);
            }
        } catch (Throwable ignored) {
            putTag(slots, 0, V8ValueType.Undefined.getId(), 0, 0);
        }
        return STATUS_THROWN;
    }

    private V8Value decode(MemorySegment slots, V8Runtime v8Runtime, int index) throws Throwable {
        long offset = SLOTS_OFFSET + index * SLOT_SIZE;
        int tag = slots.get(ValueLayout.JAVA_INT, offset);
        int length = slots.get(ValueLayout.JAVA_INT, offset + 4);
        long payload = slots.get(ValueLayout.JAVA_LONG, offset + 8);
        int typeId = tag & 0xFFFF;
        switch (typeId) {
            case 1:
                return v8Runtime.createV8ValueNull();
            case 0:
            case 2:
                return v8Runtime.createV8ValueUndefined();
            case 3:
                return v8Runtime.createV8ValueBoolean(payload != 0);
            case 4:
                return v8Runtime.createV8ValueInteger((int) payload);
            case 5:
                return v8Runtime.createV8ValueDouble(Double.longBitsToDouble(payload));
            case 6:
                return v8Runtime.createV8ValueLong(payload);
            case 8:
                return v8Runtime.createV8ValueString(readString(slots, tag, length, payload));
            case 9:
                return v8Runtime.createV8ValueZonedDateTime(payload);
            case TYPE_EXTERNAL:
                return (V8Value) ffmToExternal(v8Runtime.getHandle(), payload);
            default:
                return (V8Value) constructors[typeId].invokeExact(v8Runtime, payload);
        }
    }

    /**
     * Whether a value can be expressed as a slot, and how many string bytes it needs (or -1).
     */
    private static long encodedSize(Object value, int type) {
        if (value == null) {
            return 0;
        }
        if (type == V8ValueType.String.getId()) {
            return stringOf(value).length() * 2L + 2;
        }
        if (type == V8ValueType.BigInteger.getId()) {
            return ((V8ValueBigInteger) value).getValue().bitLength() / 8 + 16;
        }
        if (type >= V8ValueType.Script.getId() && type <= V8ValueType.Context.getId()) {
            return -1;
        }
        if (type >= TYPE_ID_COUNT || type < 0) {
            return -1;
        }
        return 0;
    }

    /**
     * Writes values into consecutive slots, or returns false (and writes nothing) if one cannot be a slot.
     */
    private boolean encode(MemorySegment slots, int firstSlot, Object[] values, int[] valueTypes) {
        long bytes = 0;
        for (int i = 0; i < values.length; ++i) {
            long size = encodedSize(values[i], valueTypes[i]);
            if (size < 0) {
                return false;
            }
            bytes += size;
        }
        MemorySegment strings = bytes > 0 ? reserve(bytes) : MemorySegment.NULL;
        long used = 0;
        for (int i = 0; i < values.length; ++i) {
            used = put(slots, firstSlot + i, values[i], valueTypes[i], strings, used);
        }
        return true;
    }

    private void encodeResult(MemorySegment slots, V8Value value, int type) {
        long bytes = encodedSize(value, type);
        if (bytes < 0) {
            putTag(slots, 0, V8ValueType.Undefined.getId(), 0, 0);
            return;
        }
        MemorySegment strings = bytes > 0 ? reserve(bytes) : MemorySegment.NULL;
        put(slots, 0, value, type, strings, 0);
        if (value instanceof V8ValueReference) {
            slots.set(ValueLayout.JAVA_LONG, RELEASING_HANDLE_OFFSET, ((V8ValueReference) value).getHandle());
        }
    }

    private MemorySegment exchange() {
        try {
            return ((MemorySegment) exchangeHandle.invokeExact()).reinterpret(EXCHANGE_SIZE);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    private Object finish(MemorySegment slots, long v8RuntimeHandle, int status) {
        throwIfFailed(status);
        try {
            return decode(slots, runtime(v8RuntimeHandle), 0);
        } catch (Throwable throwable) {
            throw sneakyThrow(throwable);
        }
    }

    @Override
    public Object functionCall(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType,
            Object receiver, int receiverType, boolean returnResult, Object[] values, int[] valueTypes) {
        int argc = values == null ? 0 : values.length;
        if (argc + 1 > SLOT_COUNT) {
            return super.functionCall(
                    v8RuntimeHandle, v8ValueHandle, v8ValueType, receiver, receiverType, returnResult, values, valueTypes);
        }
        Object[] all = new Object[argc + 1];
        int[] allTypes = new int[argc + 1];
        all[0] = receiver;
        allTypes[0] = receiverType;
        for (int i = 0; i < argc; ++i) {
            all[i + 1] = values[i];
            allTypes[i + 1] = valueTypes[i];
        }
        MemorySegment slots = exchange();
        if (!encode(slots, 0, all, allTypes)) {
            return super.functionCall(
                    v8RuntimeHandle, v8ValueHandle, v8ValueType, receiver, receiverType, returnResult, values, valueTypes);
        }
        int status;
        try {
            status = (int) functionCallHandle.invokeExact(v8RuntimeHandle, v8ValueHandle, argc, returnResult ? 1 : 0);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
        return finish(slots, v8RuntimeHandle, status);
    }

    @Override
    public Object objectCreate(long v8RuntimeHandle) {
        MemorySegment slots = exchange();
        int status;
        try {
            status = (int) objectCreateHandle.invokeExact(v8RuntimeHandle);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
        return finish(slots, v8RuntimeHandle, status);
    }

    @Override
    public Object objectGet(long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object key, int keyType) {
        MemorySegment slots = exchange();
        if (!encode(slots, 0, new Object[]{key}, new int[]{keyType})) {
            return super.objectGet(v8RuntimeHandle, v8ValueHandle, v8ValueType, key, keyType);
        }
        return finish(slots, v8RuntimeHandle, objectGetSlot(v8RuntimeHandle, v8ValueHandle, v8ValueType, false));
    }

    @Override
    public boolean objectGetBoolean(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object key, int keyType, boolean[] primitiveFlags) {
        MemorySegment slots = exchange();
        if (!encode(slots, 0, new Object[]{key}, new int[]{keyType})) {
            return super.objectGetBoolean(v8RuntimeHandle, v8ValueHandle, v8ValueType, key, keyType, primitiveFlags);
        }
        throwIfFailed(objectGetSlot(v8RuntimeHandle, v8ValueHandle, v8ValueType, true));
        if (typeId(slots, 0) == V8ValueType.Boolean.getId()) {
            return payload(slots, 0) != 0;
        }
        primitiveFlags[0] = false;
        return false;
    }

    @Override
    public double objectGetDouble(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object key, int keyType, boolean[] primitiveFlags) {
        MemorySegment slots = exchange();
        if (!encode(slots, 0, new Object[]{key}, new int[]{keyType})) {
            return super.objectGetDouble(v8RuntimeHandle, v8ValueHandle, v8ValueType, key, keyType, primitiveFlags);
        }
        throwIfFailed(objectGetSlot(v8RuntimeHandle, v8ValueHandle, v8ValueType, true));
        int typeId = typeId(slots, 0);
        if (typeId == V8ValueType.Integer.getId()) {
            return (int) payload(slots, 0);
        }
        if (typeId == V8ValueType.Double.getId()) {
            return Double.longBitsToDouble(payload(slots, 0));
        }
        primitiveFlags[0] = false;
        return 0;
    }

    @Override
    public int objectGetInteger(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object key, int keyType, boolean[] primitiveFlags) {
        MemorySegment slots = exchange();
        if (!encode(slots, 0, new Object[]{key}, new int[]{keyType})) {
            return super.objectGetInteger(v8RuntimeHandle, v8ValueHandle, v8ValueType, key, keyType, primitiveFlags);
        }
        throwIfFailed(objectGetSlot(v8RuntimeHandle, v8ValueHandle, v8ValueType, true));
        if (typeId(slots, 0) == V8ValueType.Integer.getId()) {
            return (int) payload(slots, 0);
        }
        primitiveFlags[0] = false;
        return 0;
    }

    @Override
    public long objectGetLong(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object key, int keyType, boolean[] primitiveFlags) {
        MemorySegment slots = exchange();
        if (!encode(slots, 0, new Object[]{key}, new int[]{keyType})) {
            return super.objectGetLong(v8RuntimeHandle, v8ValueHandle, v8ValueType, key, keyType, primitiveFlags);
        }
        throwIfFailed(objectGetSlot(v8RuntimeHandle, v8ValueHandle, v8ValueType, true));
        if (typeId(slots, 0) == V8ValueType.Long.getId()) {
            return payload(slots, 0);
        }
        primitiveFlags[0] = false;
        return 0;
    }

    private int objectGetSlot(long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, boolean primitiveOnly) {
        try {
            return (int) objectGetHandle.invokeExact(v8RuntimeHandle, v8ValueHandle, v8ValueType, primitiveOnly ? 1 : 0);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    @Override
    public String objectGetString(long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object key, int keyType) {
        MemorySegment slots = exchange();
        if (!encode(slots, 0, new Object[]{key}, new int[]{keyType})) {
            return super.objectGetString(v8RuntimeHandle, v8ValueHandle, v8ValueType, key, keyType);
        }
        throwIfFailed(objectGetSlot(v8RuntimeHandle, v8ValueHandle, v8ValueType, true));
        if (typeId(slots, 0) != V8ValueType.String.getId()) {
            return null;
        }
        return readString(
                slots,
                slots.get(ValueLayout.JAVA_INT, SLOTS_OFFSET),
                slots.get(ValueLayout.JAVA_INT, SLOTS_OFFSET + 4),
                slots.get(ValueLayout.JAVA_LONG, SLOTS_OFFSET + 8));
    }

    @Override
    public boolean objectSet(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object[] keysAndValues, int[] valueTypes) {
        int length = keysAndValues == null ? 0 : keysAndValues.length;
        if (length == 0 || length % 2 != 0) {
            return false;
        }
        MemorySegment slots = exchange();
        if (length > SLOT_COUNT || !encode(slots, 0, keysAndValues, valueTypes)) {
            return super.objectSet(v8RuntimeHandle, v8ValueHandle, v8ValueType, keysAndValues, valueTypes);
        }
        int status;
        try {
            status = (int) objectSetHandle.invokeExact(v8RuntimeHandle, v8ValueHandle, length / 2);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
        throwIfFailed(status);
        return payload(slots, 0) != 0;
    }

    private static long payload(MemorySegment slots, int index) {
        return slots.get(ValueLayout.JAVA_LONG, SLOTS_OFFSET + index * SLOT_SIZE + 8);
    }

    /**
     * Writes one value into a slot. Strings and big integers go into the reserved strings buffer at used.
     *
     * @return the new used length of the strings buffer
     */
    private static long put(MemorySegment slots, int index, Object value, int type, MemorySegment strings, long used) {
        if (value == null) {
            putTag(slots, index, V8ValueType.Null.getId(), 0, 0);
            return used;
        }
        switch (type) {
            case 0:
            case 1:
            case 2:
                putTag(slots, index, type, 0, 0);
                return used;
            case 3:
                putTag(slots, index, type, 0, ((V8ValueBoolean) value).toPrimitive() ? 1 : 0);
                return used;
            case 4:
                putTag(slots, index, type, 0, ((V8ValueInteger) value).toPrimitive());
                return used;
            case 5:
                putTag(slots, index, type, 0, Double.doubleToRawLongBits(((V8ValueDouble) value).toPrimitive()));
                return used;
            case 6:
                putTag(slots, index, type, 0, ((V8ValueLong) value).toPrimitive());
                return used;
            case 7:
                return putBigInteger(slots, index, ((V8ValueBigInteger) value).getValue(), strings, used);
            case 8:
                return putString(slots, index, stringOf(value), strings, used);
            case 9:
                putTag(slots, index, type, 0, ((V8ValueZonedDateTime) value).toPrimitive());
                return used;
            default:
                putTag(slots, index, type, 0, ((IV8ValueReference) value).getHandle());
                return used;
        }
    }

    private static long putBigInteger(MemorySegment slots, int index, BigInteger bigInteger, MemorySegment strings, long used) {
        BigInteger magnitude = bigInteger.abs();
        int words = (magnitude.bitLength() + 63) / 64;
        long offset = (used + 7) & ~7L;
        for (int i = 0; i < words; ++i) {
            strings.set(ValueLayout.JAVA_LONG_UNALIGNED, offset + i * 8L, magnitude.shiftRight(i * 64).longValue());
        }
        int flags = bigInteger.signum() < 0 ? FLAG_NEGATIVE : 0;
        putTag(slots, index, V8ValueType.BigInteger.getId() | (flags << 16), words, strings.address() + offset);
        return offset + words * 8L;
    }

    private static long putString(MemorySegment slots, int index, String value, MemorySegment strings, long used) {
        int length = value.length();
        long offset = (used + 1) & ~1L;
        MemorySegment.copy(value.toCharArray(), 0, strings, ValueLayout.JAVA_CHAR_UNALIGNED, offset, length);
        putTag(slots, index, V8ValueType.String.getId(), length, strings.address() + offset);
        return offset + length * 2L;
    }

    private static String stringOf(Object value) {
        if (value instanceof String) {
            return (String) value;
        }
        return ((V8ValueString) value).getValue();
    }

    private static void putTag(MemorySegment slots, int index, int tag, int length, long payload) {
        long offset = SLOTS_OFFSET + index * SLOT_SIZE;
        slots.set(ValueLayout.JAVA_INT, offset, tag);
        slots.set(ValueLayout.JAVA_INT, offset + 4, length);
        slots.set(ValueLayout.JAVA_LONG, offset + 8, payload);
    }

    private static String readString(MemorySegment slots, int tag, int length, long offset) {
        MemorySegment scratch = slots.get(ValueLayout.ADDRESS, 0).reinterpret(offset + length * 2L);
        if (((tag >>> 16) & FLAG_ONE_BYTE) != 0) {
            byte[] bytes = new byte[length];
            MemorySegment.copy(scratch, ValueLayout.JAVA_BYTE, offset, bytes, 0, length);
            return new String(bytes, StandardCharsets.ISO_8859_1);
        }
        char[] utf16 = new char[length];
        MemorySegment.copy(scratch, ValueLayout.JAVA_CHAR_UNALIGNED, offset, utf16, 0, length);
        return new String(utf16);
    }

    @Override
    public int batchArrayGet(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, Object[] v8Values, int startIndex, int endIndex) {
        int written = 0;
        int from = startIndex;
        while (from < endIndex && written < v8Values.length) {
            int count = Math.min(SLOT_COUNT, Math.min(endIndex - from, v8Values.length - written));
            MemorySegment slots = exchange();
            int status;
            try {
                status = (int) batchArrayGetHandle.invokeExact(v8RuntimeHandle, v8ValueHandle, v8ValueType, from, count);
            } catch (Throwable t) {
                throw sneakyThrow(t);
            }
            throwIfFailed(status);
            int got = decodeSlots(slots, v8RuntimeHandle, v8Values, written);
            written += got;
            from += got;
            if (got < count) {
                break;
            }
        }
        return written;
    }

    @Override
    public int batchObjectGet(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType,
            Object[] v8ValueKeys, int[] v8ValueKeyTypes, Object[] v8ValueValues, int length) {
        int total = Math.min(length, Math.min(v8ValueKeys.length, v8ValueValues.length));
        for (int i = 0; i < total; ++i) {
            if (encodedSize(v8ValueKeys[i], v8ValueKeyTypes[i]) < 0) {
                return super.batchObjectGet(
                        v8RuntimeHandle, v8ValueHandle, v8ValueType, v8ValueKeys, v8ValueKeyTypes, v8ValueValues, length);
            }
        }
        int written = 0;
        while (written < total) {
            int count = Math.min(SLOT_COUNT, total - written);
            Object[] keys = new Object[count];
            int[] keyTypes = new int[count];
            System.arraycopy(v8ValueKeys, written, keys, 0, count);
            System.arraycopy(v8ValueKeyTypes, written, keyTypes, 0, count);
            MemorySegment slots = exchange();
            encode(slots, 0, keys, keyTypes);
            int status;
            try {
                status = (int) batchObjectGetHandle.invokeExact(v8RuntimeHandle, v8ValueHandle, count);
            } catch (Throwable t) {
                throw sneakyThrow(t);
            }
            throwIfFailed(status);
            int got = decodeSlots(slots, v8RuntimeHandle, v8ValueValues, written);
            written += got;
            if (got < count) {
                break;
            }
        }
        return written;
    }

    /**
     * Decodes the slots a batch get wrote (their count is in the exchange) into target from offset.
     *
     * @return the number of values decoded
     */
    private int decodeSlots(MemorySegment slots, long v8RuntimeHandle, Object[] target, int offset) {
        int got = slots.get(ValueLayout.JAVA_INT, ARGC_OFFSET);
        try {
            V8Runtime v8Runtime = runtime(v8RuntimeHandle);
            for (int i = 0; i < got; ++i) {
                target[offset + i] = decode(slots, v8Runtime, i);
            }
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
        return got;
    }

    @Override
    public Object objectInvoke(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType,
            String functionName, boolean returnResult, Object[] values, int[] valueTypes) {
        int argc = values == null ? 0 : values.length;
        if (argc + 1 > SLOT_COUNT) {
            return super.objectInvoke(
                    v8RuntimeHandle, v8ValueHandle, v8ValueType, functionName, returnResult, values, valueTypes);
        }
        Object[] all = new Object[argc + 1];
        int[] allTypes = new int[argc + 1];
        all[0] = functionName;
        allTypes[0] = V8ValueType.String.getId();
        for (int i = 0; i < argc; ++i) {
            all[i + 1] = values[i];
            allTypes[i + 1] = valueTypes[i];
        }
        MemorySegment slots = exchange();
        if (!encode(slots, 0, all, allTypes)) {
            return super.objectInvoke(
                    v8RuntimeHandle, v8ValueHandle, v8ValueType, functionName, returnResult, values, valueTypes);
        }
        int status;
        try {
            status = (int) objectInvokeHandle.invokeExact(
                    v8RuntimeHandle, v8ValueHandle, v8ValueType, argc, returnResult ? 1 : 0);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
        if (status == STATUS_NULL) {
            return null;
        }
        return finish(slots, v8RuntimeHandle, status);
    }

    @Override
    public boolean objectSetPrivateProperty(
            long v8RuntimeHandle, long v8ValueHandle, int v8ValueType, String key, Object value, int valueType) {
        MemorySegment slots = exchange();
        Object[] pair = new Object[]{key, value};
        int[] pairTypes = new int[]{V8ValueType.String.getId(), valueType};
        if (key == null || !encode(slots, 0, pair, pairTypes)) {
            return super.objectSetPrivateProperty(v8RuntimeHandle, v8ValueHandle, v8ValueType, key, value, valueType);
        }
        int status;
        try {
            status = (int) objectSetPrivatePropertyHandle.invokeExact(v8RuntimeHandle, v8ValueHandle);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
        throwIfFailed(status);
        return payload(slots, 0) != 0;
    }

    @Override
    public void removeReferenceHandle(long v8RuntimeHandle, long referenceHandle, int referenceType) {
        try {
            removeReferenceHandle.invokeExact(v8RuntimeHandle, referenceHandle);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    /**
     * The native per-thread buffer for strings going to native code, valid until the next reserve on this thread.
     */
    private MemorySegment reserve(long bytes) {
        try {
            return ((MemorySegment) reserveHandle.invokeExact(bytes)).reinterpret(bytes);
        } catch (Throwable t) {
            throw sneakyThrow(t);
        }
    }

    private V8Runtime runtime(long v8RuntimeHandle) throws ReflectiveOperationException {
        Map<Long, V8Runtime> map = v8RuntimeMap;
        if (map == null) {
            map = runtimeMap();
            v8RuntimeMap = map;
        }
        V8Runtime v8Runtime = map.get(v8RuntimeHandle);
        if (v8Runtime == null) {
            throw new IllegalStateException("V8 runtime " + v8RuntimeHandle + " is not registered");
        }
        return v8Runtime;
    }

    @SuppressWarnings("unchecked")
    private static Map<Long, V8Runtime> runtimeMap() throws ReflectiveOperationException {
        Field field = V8Host.class.getDeclaredField("v8RuntimeMap");
        field.setAccessible(true);
        return (Map<Long, V8Runtime>) field.get(V8Host.getV8Instance());
    }

    private void throwIfFailed(int status) {
        if (status != STATUS_OK) {
            Throwable throwable = ffmTakeThrowable();
            throw sneakyThrow(throwable == null ? new IllegalStateException("Native FFM call failed") : throwable);
        }
    }

    private static int typeId(MemorySegment slots, int index) {
        return slots.get(ValueLayout.JAVA_INT, SLOTS_OFFSET + index * SLOT_SIZE) & 0xFFFF;
    }
}
