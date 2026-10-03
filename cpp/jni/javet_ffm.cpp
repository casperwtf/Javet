/*
 *   Copyright (c) 2021-2026. caoccao.com Sam Cao
 *   All rights reserved.

 *   Licensed under the Apache License, Version 2.0 (the "License");
 *   you may not use this file except in compliance with the License.
 *   You may obtain a copy of the License at

 *   http://www.apache.org/licenses/LICENSE-2.0

 *   Unless required by applicable law or agreed to in writing, software
 *   distributed under the License is distributed on an "AS IS" BASIS,
 *   WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 *   See the License for the specific language governing permissions and
 *   limitations under the License.
 */

#include <cstring>
#include <vector>

#include "javet_callbacks.h"
#include "javet_converter.h"
#include "javet_enums.h"
#include "javet_exceptions.h"
#include "javet_ffm.h"
#include "javet_monitor.h"
#include "javet_native.h"
#include "javet_v8_runtime.h"

namespace Javet {
    namespace FFM {
        using V8ValueType = Javet::Enums::V8ValueType;

        static thread_local Exchange exchange{};
        static thread_local std::vector<uint8_t> scratch;
        static thread_local std::vector<uint8_t> javaStrings;
        static thread_local jthrowable stashedThrowable = nullptr;
        static CallbackUpcall callbackUpcall = nullptr;

        static inline JNIEnv* CurrentJNIEnv() noexcept {
            JNIEnv* jniEnv = nullptr;
            if (GlobalJavaVM == nullptr
                || GlobalJavaVM->GetEnv(reinterpret_cast<void**>(&jniEnv), SUPPORTED_JNI_VERSION) != JNI_OK) {
                return nullptr;
            }
            return jniEnv;
        }

        // The shared helpers report errors by throwing on the JNIEnv. An FFM downcall
        // drops pending JNI exceptions, so park it for V8NativeFfm.ffmTakeThrowable().
        static int32_t Finish(JNIEnv* jniEnv) noexcept {
            if (!jniEnv->ExceptionCheck()) {
                return STATUS_OK;
            }
            jthrowable throwable = jniEnv->ExceptionOccurred();
            jniEnv->ExceptionClear();
            if (stashedThrowable != nullptr) {
                jniEnv->DeleteGlobalRef(stashedThrowable);
            }
            stashedThrowable = static_cast<jthrowable>(jniEnv->NewGlobalRef(throwable));
            jniEnv->DeleteLocalRef(throwable);
            return STATUS_THROWN;
        }

        static inline void ResetScratch() noexcept {
            exchange.scratchLength = 0;
        }

        static uint8_t* ReserveScratch(size_t bytes, int64_t& offset) noexcept {
            size_t start = (static_cast<size_t>(exchange.scratchLength) + 1) & ~static_cast<size_t>(1);
            if (scratch.size() < start + bytes) {
                scratch.resize((start + bytes) * 2);
            }
            exchange.scratch = scratch.data();
            exchange.scratchLength = static_cast<int64_t>(start + bytes);
            offset = static_cast<int64_t>(start);
            return scratch.data() + start;
        }

        static inline void SetSlot(Slot& slot, V8ValueType type, int64_t payload) noexcept {
            slot.tag = static_cast<int32_t>(type);
            slot.length = 0;
            slot.payload = payload;
        }

        static inline void EncodeReference(
            V8Isolate* v8Isolate,
            const V8LocalValue& v8Value,
            Slot& slot,
            int32_t type) noexcept {
            slot.tag = type;
            slot.length = 0;
            slot.payload = TO_JAVA_LONG(new V8PersistentValue(v8Isolate, v8Value));
            INCREASE_COUNTER(Javet::Monitor::CounterType::NewPersistentReference);
        }

        static int32_t TypedArrayType(const V8LocalValue& v8Value) noexcept {
            if (v8Value->IsUint8Array()) return static_cast<int32_t>(V8ValueType::Uint8Array);
            if (v8Value->IsInt32Array()) return static_cast<int32_t>(V8ValueType::Int32Array);
            if (v8Value->IsFloat64Array()) return static_cast<int32_t>(V8ValueType::Float64Array);
            if (v8Value->IsFloat32Array()) return static_cast<int32_t>(V8ValueType::Float32Array);
            if (v8Value->IsInt8Array()) return static_cast<int32_t>(V8ValueType::Int8Array);
            if (v8Value->IsInt16Array()) return static_cast<int32_t>(V8ValueType::Int16Array);
            if (v8Value->IsUint16Array()) return static_cast<int32_t>(V8ValueType::Uint16Array);
            if (v8Value->IsUint32Array()) return static_cast<int32_t>(V8ValueType::Uint32Array);
            if (v8Value->IsUint8ClampedArray()) return static_cast<int32_t>(V8ValueType::Uint8ClampedArray);
            if (v8Value->IsBigInt64Array()) return static_cast<int32_t>(V8ValueType::BigInt64Array);
            if (v8Value->IsBigUint64Array()) return static_cast<int32_t>(V8ValueType::BigUint64Array);
            if (v8Value->IsFloat16Array()) return static_cast<int32_t>(V8ValueType::Float16Array);
            return TYPE_EXTERNAL;
        }

        // Mirrors Converter::ToExternalV8Value. Anything Java cannot build from a
        // handle alone (array buffers, modules) goes out as TYPE_EXTERNAL and is
        // converted by the JNI path in V8NativeFfm.ffmToExternal().
        static int32_t ObjectType(const V8LocalValue& v8Value) noexcept {
            if (v8Value->IsArray()) return static_cast<int32_t>(V8ValueType::Array);
            if (v8Value->IsTypedArray()) return TypedArrayType(v8Value);
            if (v8Value->IsDataView()) return static_cast<int32_t>(V8ValueType::DataView);
            if (v8Value->IsArrayBuffer() || v8Value->IsSharedArrayBuffer()) return TYPE_EXTERNAL;
            if (v8Value->IsWeakMap()) return static_cast<int32_t>(V8ValueType::WeakMap);
            if (v8Value->IsWeakSet()) return static_cast<int32_t>(V8ValueType::WeakSet);
            if (v8Value->IsMap()) return static_cast<int32_t>(V8ValueType::Map);
            if (v8Value->IsSet()) return static_cast<int32_t>(V8ValueType::Set);
            if (v8Value->IsMapIterator() || v8Value->IsSetIterator() || v8Value->IsGeneratorObject()) {
                return static_cast<int32_t>(V8ValueType::Iterator);
            }
            if (v8Value->IsArgumentsObject()) return static_cast<int32_t>(V8ValueType::Arguments);
            if (v8Value->IsPromise()) return static_cast<int32_t>(V8ValueType::Promise);
            if (v8Value->IsRegExp()) return static_cast<int32_t>(V8ValueType::RegExp);
            if (v8Value->IsProxy()) return static_cast<int32_t>(V8ValueType::Proxy);
            if (v8Value->IsFunction()) return static_cast<int32_t>(V8ValueType::Function);
            if (v8Value->IsNativeError()) return static_cast<int32_t>(V8ValueType::Error);
            if (v8Value->IsSymbolObject()) return static_cast<int32_t>(V8ValueType::SymbolObject);
            if (v8Value->IsStringObject()) return static_cast<int32_t>(V8ValueType::StringObject);
            if (v8Value->IsNumberObject()) return static_cast<int32_t>(V8ValueType::DoubleObject);
            if (v8Value->IsBooleanObject()) return static_cast<int32_t>(V8ValueType::BooleanObject);
            if (v8Value->IsBigIntObject()) return static_cast<int32_t>(V8ValueType::BigIntObject);
            if (v8Value->IsModule()) return TYPE_EXTERNAL;
            return static_cast<int32_t>(V8ValueType::Object);
        }

        static void EncodeString(V8Isolate* v8Isolate, const V8LocalString& v8String, Slot& slot) noexcept {
            const int length = v8String->Length();
            int64_t offset = 0;
            slot.length = length;
            if (v8String->IsOneByte()) {
                uint8_t* buffer = ReserveScratch(static_cast<size_t>(length), offset);
                v8String->WriteOneByteV2(v8Isolate, 0, length, buffer);
                slot.tag = static_cast<int32_t>(V8ValueType::String) | (FLAG_ONE_BYTE << 16);
            }
            else {
                uint8_t* buffer = ReserveScratch(static_cast<size_t>(length) * 2, offset);
                v8String->WriteV2(v8Isolate, 0, length, reinterpret_cast<uint16_t*>(buffer));
                slot.tag = static_cast<int32_t>(V8ValueType::String);
            }
            slot.payload = offset;
        }

        static void Encode(
            V8Isolate* v8Isolate,
            const V8LocalContext& v8Context,
            const V8LocalValue& v8Value,
            Slot& slot) noexcept {
            if (v8Value.IsEmpty() || v8Value->IsUndefined()) {
                SetSlot(slot, V8ValueType::Undefined, 0);
            }
            else if (v8Value->IsNull()) {
                SetSlot(slot, V8ValueType::Null, 0);
            }
            else if (v8Value->IsObject()) {
                if (v8Value->IsDate()) {
                    auto v8Date = v8Value.As<v8::Date>();
                    SetSlot(slot, V8ValueType::ZonedDateTime, static_cast<int64_t>(v8Date->ValueOf()));
                }
                else {
                    EncodeReference(v8Isolate, v8Value, slot, ObjectType(v8Value));
                }
            }
            else if (v8Value->IsBoolean()) {
                SetSlot(slot, V8ValueType::Boolean, v8Value->IsTrue() ? 1 : 0);
            }
            else if (v8Value->IsInt32()) {
                SetSlot(slot, V8ValueType::Integer, v8Value.As<v8::Int32>()->Value());
            }
            else if (v8Value->IsNumber()) {
                double number = v8Value.As<v8::Number>()->Value();
                int64_t bits;
                std::memcpy(&bits, &number, sizeof(bits));
                SetSlot(slot, V8ValueType::Double, bits);
            }
            else if (v8Value->IsString()) {
                EncodeString(v8Isolate, v8Value.As<v8::String>(), slot);
            }
            else if (v8Value->IsSymbol()) {
                EncodeReference(v8Isolate, v8Value, slot, static_cast<int32_t>(V8ValueType::Symbol));
            }
            else if (v8Value->IsBigInt() && v8Value.As<v8::BigInt>()->WordCount() <= 1) {
                SetSlot(slot, V8ValueType::Long, v8Value.As<v8::BigInt>()->Int64Value());
            }
            else {
                EncodeReference(v8Isolate, v8Value, slot, TYPE_EXTERNAL);
            }
        }

        // Typed getters (objectGetInteger etc.) never want a handle: unwrap the
        // wrapper objects the JNI converters accept and mark the rest Invalid.
        static void EncodePrimitive(
            V8Isolate* v8Isolate,
            const V8LocalContext& v8Context,
            const V8LocalValue& v8Value,
            Slot& slot) noexcept {
            if (v8Value->IsBooleanObject()) {
                SetSlot(slot, V8ValueType::Boolean, v8Value.As<v8::BooleanObject>()->ValueOf() ? 1 : 0);
            }
            else if (v8Value->IsNumberObject()) {
                double number = v8Value.As<v8::NumberObject>()->ValueOf();
                int64_t bits;
                std::memcpy(&bits, &number, sizeof(bits));
                SetSlot(slot, V8ValueType::Double, bits);
            }
            else if (v8Value->IsBigInt()) {
                SetSlot(slot, V8ValueType::Long, v8Value.As<v8::BigInt>()->Int64Value());
            }
            else if (v8Value->IsBigIntObject()) {
                SetSlot(slot, V8ValueType::Long, v8Value.As<v8::BigIntObject>()->ValueOf()->Int64Value());
            }
            else if (v8Value->IsObject() || v8Value->IsSymbol()) {
                SetSlot(slot, V8ValueType::Invalid, 0);
            }
            else {
                Encode(v8Isolate, v8Context, v8Value, slot);
            }
        }

        static V8LocalValue Decode(
            V8Isolate* v8Isolate,
            const V8LocalContext& v8Context,
            const Slot& slot) noexcept {
            switch (static_cast<V8ValueType>(slot.tag & 0xFFFF)) {
            case V8ValueType::Null:
                return Javet::Converter::ToV8Null(v8Isolate);
            case V8ValueType::Invalid:
            case V8ValueType::Undefined:
                return Javet::Converter::ToV8Undefined(v8Isolate);
            case V8ValueType::Boolean:
                return Javet::Converter::ToV8Boolean(v8Isolate, slot.payload != 0);
            case V8ValueType::Integer:
                return Javet::Converter::ToV8Integer(v8Isolate, static_cast<int>(slot.payload));
            case V8ValueType::Double: {
                double number;
                std::memcpy(&number, &slot.payload, sizeof(number));
                return Javet::Converter::ToV8Double(v8Isolate, number);
            }
            case V8ValueType::Long:
                return Javet::Converter::ToV8Long(v8Isolate, slot.payload);
            case V8ValueType::ZonedDateTime:
                return Javet::Converter::ToV8Date(v8Context, slot.payload);
            case V8ValueType::BigInteger: {
                if (slot.length == 0) {
                    return v8::BigInt::New(v8Isolate, 0);
                }
                auto v8MaybeLocalBigInt = v8::BigInt::NewFromWords(
                    v8Context,
                    (slot.tag >> 16) & FLAG_NEGATIVE,
                    slot.length,
                    reinterpret_cast<const uint64_t*>(slot.payload));
                V8LocalBigInt v8LocalBigInt;
                if (v8MaybeLocalBigInt.ToLocal(&v8LocalBigInt)) {
                    return v8LocalBigInt;
                }
                return Javet::Converter::ToV8Undefined(v8Isolate);
            }
            case V8ValueType::String: {
                auto v8MaybeLocalString = v8::String::NewFromTwoByte(
                    v8Isolate,
                    reinterpret_cast<const uint16_t*>(slot.payload),
                    v8::NewStringType::kNormal,
                    slot.length);
                V8LocalString v8LocalString;
                if (v8MaybeLocalString.ToLocal(&v8LocalString)) {
                    return v8LocalString;
                }
                return Javet::Converter::ToV8Undefined(v8Isolate);
            }
            default:
                return V8LocalValue::New(v8Isolate, *TO_V8_PERSISTENT_VALUE_POINTER(slot.payload));
            }
        }

        static inline void EncodeResult(
            V8Isolate* v8Isolate,
            const V8LocalContext& v8Context,
            const V8LocalValue& v8Value) noexcept {
            ResetScratch();
            Encode(v8Isolate, v8Context, v8Value, exchange.slots[0]);
        }

        static inline void EncodeUndefined() noexcept {
            ResetScratch();
            SetSlot(exchange.slots[0], V8ValueType::Undefined, 0);
        }

        bool TryCallFunction(
            Javet::Callback::JavetCallbackContextReference* callbackContextReference,
            const v8::FunctionCallbackInfo<v8::Value>& args) noexcept {
            CallbackUpcall upcall = callbackUpcall;
            const int32_t flags = callbackContextReference->ffmFlags;
            const int argc = args.Length();
            if (upcall == nullptr || flags < 0 || argc + 1 > SLOT_COUNT) {
                return false;
            }
            JNIEnv* jniEnv = CurrentJNIEnv();
            if (jniEnv == nullptr) {
                return false;
            }
            auto v8Isolate = args.GetIsolate();
            V8IsolateScope v8IsolateScope(v8Isolate);
            V8HandleScope v8HandleScope(v8Isolate);
            auto v8Context = v8Isolate->GetCurrentContext();
            if (v8Context.IsEmpty()) {
                return false;
            }
            auto v8Runtime = Javet::V8Runtime::FromV8Context(v8Context);
            if (v8Runtime == nullptr) {
                return false;
            }
            Javet::ExternalExceptionScope externalExceptionScope(jniEnv, v8Runtime);
            V8ContextScope v8ContextScope(v8Context);
            ResetScratch();
            exchange.argc = argc;
            exchange.flags = flags;
            exchange.releasingHandle = 0;
            Slot* slots = exchange.slots;
            if ((flags & CALLBACK_THIS) != 0) {
                Encode(v8Isolate, v8Context, args.This(), slots[0]);
            }
            for (int i = 0; i < argc; ++i) {
                Encode(v8Isolate, v8Context, args[i], slots[i + 1]);
            }
            const int64_t status = upcall(TO_JAVA_LONG(v8Runtime), TO_JAVA_LONG(callbackContextReference));
            if (status == STATUS_OK) {
                if ((flags & CALLBACK_RESULT) != 0) {
                    args.GetReturnValue().Set(Decode(v8Isolate, v8Context, slots[0]));
                }
                // Java closed the reference result before we read it and left the release to us.
                if (exchange.releasingHandle == RELEASED_BY_JAVA) {
                    auto v8PersistentValuePointer = TO_V8_PERSISTENT_VALUE_POINTER(slots[0].payload);
                    v8PersistentValuePointer->Reset();
                    delete v8PersistentValuePointer;
                    INCREASE_COUNTER(Javet::Monitor::CounterType::DeletePersistentReference);
                }
                exchange.releasingHandle = 0;
            }
            else {
                exchange.releasingHandle = 0;
                V8LocalValue v8LocalMessage = Decode(v8Isolate, v8Context, slots[0]);
                if (!v8LocalMessage->IsString()) {
                    v8LocalMessage = Javet::Converter::ToV8String(v8Isolate, "Uncaught JavaError in function callback");
                }
                v8Isolate->ThrowException(v8::Exception::Error(v8LocalMessage.As<v8::String>()));
            }
            return true;
        }
    }
}

using namespace Javet::FFM;

extern "C" {
    JNIEXPORT int32_t JNICALL javet_ffm_version() {
        return ABI_VERSION;
    }

    JNIEXPORT void* JNICALL javet_ffm_exchange() {
        return &exchange;
    }

    JNIEXPORT void* JNICALL javet_ffm_reserve(int64_t bytes) {
        if (javaStrings.size() < static_cast<size_t>(bytes)) {
            javaStrings.resize(static_cast<size_t>(bytes) * 2);
        }
        return javaStrings.data();
    }

    JNIEXPORT void JNICALL javet_ffm_set_callback(void* upcall) {
        callbackUpcall = reinterpret_cast<CallbackUpcall>(upcall);
    }

    JNIEXPORT int32_t JNICALL javet_ffm_function_call(
        int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t argc, int32_t resultRequired) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_AND_VALUE_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle, v8ValueHandle);
        if (v8LocalValue->IsFunction()) {
            V8TryCatch v8TryCatch(v8Isolate);
            Slot* slots = exchange.slots;
            auto v8LocalReceiver = Decode(v8Isolate, v8Context, slots[0]);
            V8LocalValue v8Values[SLOT_COUNT];
            for (int32_t i = 0; i < argc; ++i) {
                v8Values[i] = Decode(v8Isolate, v8Context, slots[i + 1]);
            }
            auto v8MaybeLocalValueResult = v8LocalValue.As<v8::Function>()->Call(
                v8Context, v8LocalReceiver, argc, argc > 0 ? v8Values : nullptr);
            if (v8TryCatch.HasCaught()) {
                Javet::Exceptions::ThrowJavetExecutionException(jniEnv, v8Runtime, v8Context, v8TryCatch);
                return Finish(jniEnv);
            }
            if (resultRequired && !v8MaybeLocalValueResult.IsEmpty()) {
                EncodeResult(v8Isolate, v8Context, v8MaybeLocalValueResult.ToLocalChecked());
                return Finish(jniEnv);
            }
        }
        EncodeUndefined();
        return Finish(jniEnv);
    }

    JNIEXPORT int32_t JNICALL javet_ffm_object_create(int64_t v8RuntimeHandle) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle);
        auto v8LocalObject = v8::Object::New(v8Isolate);
        if (v8LocalObject.IsEmpty()) {
            EncodeUndefined();
        }
        else {
            EncodeResult(v8Isolate, v8Context, v8LocalObject);
        }
        return Finish(jniEnv);
    }

    JNIEXPORT int32_t JNICALL javet_ffm_object_get(
        int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t v8ValueType, int32_t primitiveOnly) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_AND_VALUE_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle, v8ValueHandle);
        V8TryCatch v8TryCatch(v8Isolate);
        V8LocalValue v8LocalTarget = v8LocalValue;
        if (IS_V8_SYMBOL(v8ValueType)) {
            auto v8MaybeLocalObject = v8LocalTarget->ToObject(v8Context);
            if (!v8MaybeLocalObject.IsEmpty()) {
                v8LocalTarget = v8MaybeLocalObject.ToLocalChecked();
            }
        }
        if (v8LocalTarget->IsObject()) {
            const Slot& keySlot = exchange.slots[0];
            auto v8LocalObject = v8LocalTarget.As<v8::Object>();
            V8MaybeLocalValue v8MaybeLocalValue;
            if ((keySlot.tag & 0xFFFF) == static_cast<int32_t>(V8ValueType::Integer)) {
                v8MaybeLocalValue = v8LocalObject->Get(v8Context, static_cast<uint32_t>(keySlot.payload));
            }
            else {
                v8MaybeLocalValue = v8LocalObject->Get(v8Context, Decode(v8Isolate, v8Context, keySlot));
            }
            if (v8TryCatch.HasCaught()) {
                Javet::Exceptions::ThrowJavetExecutionException(jniEnv, v8Runtime, v8Context, v8TryCatch);
                return Finish(jniEnv);
            }
            if (!v8MaybeLocalValue.IsEmpty()) {
                if (primitiveOnly) {
                    ResetScratch();
                    EncodePrimitive(v8Isolate, v8Context, v8MaybeLocalValue.ToLocalChecked(), exchange.slots[0]);
                }
                else {
                    EncodeResult(v8Isolate, v8Context, v8MaybeLocalValue.ToLocalChecked());
                }
                return Finish(jniEnv);
            }
            Javet::Exceptions::HandlePendingException(jniEnv, v8Runtime, v8Context);
        }
        else if (v8TryCatch.HasCaught()) {
            Javet::Exceptions::ThrowJavetExecutionException(jniEnv, v8Runtime, v8Context, v8TryCatch);
            return Finish(jniEnv);
        }
        EncodeUndefined();
        return Finish(jniEnv);
    }

    JNIEXPORT int32_t JNICALL javet_ffm_object_set(
        int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t pairCount) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_AND_VALUE_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle, v8ValueHandle);
        bool success = false;
        if (v8LocalValue->IsObject() && pairCount > 0) {
            // A setter may call back into Java and reuse the exchange, so decode every pair first.
            Slot* slots = exchange.slots;
            V8LocalValue v8Keys[SLOT_COUNT / 2];
            V8LocalValue v8Values[SLOT_COUNT / 2];
            bool integerKeys[SLOT_COUNT / 2];
            uint32_t indexes[SLOT_COUNT / 2];
            for (int32_t i = 0; i < pairCount; ++i) {
                const Slot& keySlot = slots[i * 2];
                integerKeys[i] = (keySlot.tag & 0xFFFF) == static_cast<int32_t>(V8ValueType::Integer);
                indexes[i] = static_cast<uint32_t>(keySlot.payload);
                if (!integerKeys[i]) {
                    v8Keys[i] = Decode(v8Isolate, v8Context, keySlot);
                }
                v8Values[i] = Decode(v8Isolate, v8Context, slots[i * 2 + 1]);
            }
            V8TryCatch v8TryCatch(v8Isolate);
            auto v8LocalObject = v8LocalValue.As<v8::Object>();
            success = true;
            for (int32_t i = 0; i < pairCount && success; ++i) {
                V8MaybeBool v8MaybeBool = integerKeys[i]
                    ? v8LocalObject->Set(v8Context, indexes[i], v8Values[i])
                    : v8LocalObject->Set(v8Context, v8Keys[i], v8Values[i]);
                if (v8TryCatch.HasCaught()) {
                    Javet::Exceptions::ThrowJavetExecutionException(jniEnv, v8Runtime, v8Context, v8TryCatch);
                    return Finish(jniEnv);
                }
                if (v8MaybeBool.IsNothing()) {
                    Javet::Exceptions::HandlePendingException(jniEnv, v8Runtime, v8Context);
                    success = false;
                }
                else {
                    success = v8MaybeBool.FromMaybe(false);
                }
            }
        }
        ResetScratch();
        SetSlot(exchange.slots[0], V8ValueType::Boolean, success ? 1 : 0);
        return Finish(jniEnv);
    }

    JNIEXPORT void JNICALL javet_ffm_remove_reference(int64_t v8RuntimeHandle, int64_t referenceHandle) {
        if (referenceHandle == exchange.releasingHandle) {
            exchange.releasingHandle = RELEASED_BY_JAVA;
            return;
        }
        auto v8Runtime = Javet::V8Runtime::FromHandle(v8RuntimeHandle);
        auto v8Locker = v8Runtime->GetSharedV8Locker();
        auto v8PersistentDataPointer = TO_V8_PERSISTENT_DATA_POINTER(referenceHandle);
        if (!v8PersistentDataPointer->IsEmpty() && v8PersistentDataPointer->IsWeak()) {
            auto v8ValueReference = v8PersistentDataPointer->ClearWeak<Javet::Callback::V8ValueReference>();
            if (v8ValueReference != nullptr) {
                v8ValueReference->Clear();
                delete v8ValueReference;
                INCREASE_COUNTER(Javet::Monitor::CounterType::DeleteWeakCallbackReference);
            }
        }
        v8PersistentDataPointer->Reset();
        delete v8PersistentDataPointer;
        INCREASE_COUNTER(Javet::Monitor::CounterType::DeletePersistentReference);
    }
}

extern "C" {
JNIEXPORT jthrowable JNICALL Java_com_caoccao_javet_interop_V8NativeFfm_ffmTakeThrowable
(JNIEnv* jniEnv, jobject caller) {
    if (stashedThrowable == nullptr) {
        return nullptr;
    }
    auto throwable = static_cast<jthrowable>(jniEnv->NewLocalRef(stashedThrowable));
    jniEnv->DeleteGlobalRef(stashedThrowable);
    stashedThrowable = nullptr;
    return throwable;
}

JNIEXPORT void JNICALL Java_com_caoccao_javet_interop_V8NativeFfm_ffmSetExternalException
(JNIEnv* jniEnv, jobject caller, jlong v8RuntimeHandle, jthrowable throwable) {
    auto v8Runtime = Javet::V8Runtime::FromHandle(v8RuntimeHandle);
    v8Runtime->ClearExternalException(jniEnv);
    v8Runtime->externalException = static_cast<jthrowable>(jniEnv->NewGlobalRef(throwable));
    INCREASE_COUNTER(Javet::Monitor::CounterType::NewGlobalRef);
}

JNIEXPORT jobject JNICALL Java_com_caoccao_javet_interop_V8NativeFfm_ffmToExternal
(JNIEnv* jniEnv, jobject caller, jlong v8RuntimeHandle, jlong referenceHandle) {
    RUNTIME_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle);
    auto v8PersistentValuePointer = TO_V8_PERSISTENT_VALUE_POINTER(referenceHandle);
    auto v8LocalValue = v8PersistentValuePointer->Get(v8Isolate);
    v8PersistentValuePointer->Reset();
    delete v8PersistentValuePointer;
    INCREASE_COUNTER(Javet::Monitor::CounterType::DeletePersistentReference);
    return v8Runtime->SafeToExternalV8Value(jniEnv, v8Isolate, v8Context, v8LocalValue);
}
}

extern "C" {
    JNIEXPORT int32_t JNICALL javet_ffm_batch_array_get(
        int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t v8ValueType, int32_t startIndex, int32_t count) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_AND_VALUE_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle, v8ValueHandle);
        ResetScratch();
        exchange.argc = 0;
        int64_t length = -1;
        if (IS_V8_ARRAY(v8ValueType) || IS_V8_ARGUMENTS(v8ValueType)) {
            length = v8LocalValue.As<v8::Array>()->Length();
        }
        else if (v8LocalValue->IsTypedArray()) {
            length = static_cast<int64_t>(v8LocalValue.As<v8::TypedArray>()->Length());
        }
        if (length < 0 || startIndex < 0 || count <= 0) {
            return Finish(jniEnv);
        }
        int64_t available = length - startIndex;
        const int32_t actual = static_cast<int32_t>(available < count ? (available < 0 ? 0 : available) : count);
        // A getter may call back into Java and reuse the exchange: read everything first, encode last.
        auto v8LocalObject = v8LocalValue.As<v8::Object>();
        V8LocalValue v8Values[SLOT_COUNT];
        for (int32_t i = 0; i < actual; ++i) {
            auto v8MaybeLocalValue = v8LocalObject->Get(v8Context, static_cast<uint32_t>(startIndex + i));
            if (v8MaybeLocalValue.IsEmpty()) {
                if (Javet::Exceptions::HandlePendingException(jniEnv, v8Runtime, v8Context)) {
                    return Finish(jniEnv);
                }
            }
            else {
                v8Values[i] = v8MaybeLocalValue.ToLocalChecked();
            }
        }
        ResetScratch();
        for (int32_t i = 0; i < actual; ++i) {
            Encode(v8Isolate, v8Context, v8Values[i], exchange.slots[i]);
        }
        exchange.argc = actual;
        return Finish(jniEnv);
    }

    JNIEXPORT int32_t JNICALL javet_ffm_batch_object_get(int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t count) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_AND_VALUE_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle, v8ValueHandle);
        exchange.argc = 0;
        if (!v8LocalValue->IsObject() || count <= 0) {
            ResetScratch();
            return Finish(jniEnv);
        }
        Slot* slots = exchange.slots;
        bool integerKeys[SLOT_COUNT];
        uint32_t indexes[SLOT_COUNT];
        V8LocalValue v8Keys[SLOT_COUNT];
        for (int32_t i = 0; i < count; ++i) {
            integerKeys[i] = (slots[i].tag & 0xFFFF) == static_cast<int32_t>(V8ValueType::Integer);
            indexes[i] = static_cast<uint32_t>(slots[i].payload);
            if (!integerKeys[i]) {
                v8Keys[i] = Decode(v8Isolate, v8Context, slots[i]);
            }
        }
        auto v8LocalObject = v8LocalValue.As<v8::Object>();
        V8TryCatch v8TryCatch(v8Isolate);
        V8LocalValue v8Values[SLOT_COUNT];
        for (int32_t i = 0; i < count; ++i) {
            V8MaybeLocalValue v8MaybeLocalValue = integerKeys[i]
                ? v8LocalObject->Get(v8Context, indexes[i])
                : v8LocalObject->Get(v8Context, v8Keys[i]);
            if (v8TryCatch.HasCaught()) {
                Javet::Exceptions::ThrowJavetExecutionException(jniEnv, v8Runtime, v8Context, v8TryCatch);
                return Finish(jniEnv);
            }
            if (v8MaybeLocalValue.IsEmpty()) {
                if (Javet::Exceptions::HandlePendingException(jniEnv, v8Runtime, v8Context)) {
                    return Finish(jniEnv);
                }
            }
            else {
                v8Values[i] = v8MaybeLocalValue.ToLocalChecked();
            }
        }
        ResetScratch();
        for (int32_t i = 0; i < count; ++i) {
            Encode(v8Isolate, v8Context, v8Values[i], slots[i]);
        }
        exchange.argc = count;
        return Finish(jniEnv);
    }

    JNIEXPORT int32_t JNICALL javet_ffm_object_invoke(
        int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t v8ValueType, int32_t argc, int32_t resultRequired) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_AND_VALUE_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle, v8ValueHandle);
        Slot* slots = exchange.slots;
        auto v8LocalName = Decode(v8Isolate, v8Context, slots[0]);
        V8LocalValue v8Values[SLOT_COUNT];
        for (int32_t i = 0; i < argc; ++i) {
            v8Values[i] = Decode(v8Isolate, v8Context, slots[i + 1]);
        }
        V8LocalValue v8LocalTarget = v8LocalValue;
        if (IS_V8_SYMBOL(v8ValueType)) {
            auto v8MaybeLocalObject = v8LocalTarget->ToObject(v8Context);
            if (v8MaybeLocalObject.IsEmpty()) {
                if (Javet::Exceptions::HandlePendingException(jniEnv, v8Runtime, v8Context)) {
                    return Finish(jniEnv);
                }
            }
            else {
                v8LocalTarget = v8MaybeLocalObject.ToLocalChecked();
            }
        }
        if (v8LocalTarget->IsObject()) {
            auto v8LocalObject = v8LocalTarget.As<v8::Object>();
            auto v8MaybeLocalFunction = v8LocalObject->Get(v8Context, v8LocalName);
            if (v8MaybeLocalFunction.IsEmpty()) {
                Javet::Exceptions::HandlePendingException(jniEnv, v8Runtime, v8Context);
                const int32_t status = Finish(jniEnv);
                return status == STATUS_OK ? STATUS_NULL : status;
            }
            auto v8LocalFunction = v8MaybeLocalFunction.ToLocalChecked();
            if (!v8LocalFunction->IsFunction()) {
                return STATUS_NULL;
            }
            V8TryCatch v8TryCatch(v8Isolate);
            auto v8MaybeLocalValueResult = v8LocalFunction.As<v8::Function>()->Call(
                v8Context, v8LocalObject, argc, argc > 0 ? v8Values : nullptr);
            if (v8TryCatch.HasCaught()) {
                Javet::Exceptions::ThrowJavetExecutionException(jniEnv, v8Runtime, v8Context, v8TryCatch);
                return Finish(jniEnv);
            }
            if (resultRequired && !v8MaybeLocalValueResult.IsEmpty()) {
                EncodeResult(v8Isolate, v8Context, v8MaybeLocalValueResult.ToLocalChecked());
                return Finish(jniEnv);
            }
        }
        EncodeUndefined();
        return Finish(jniEnv);
    }

    JNIEXPORT int32_t JNICALL javet_ffm_object_set_private_property(int64_t v8RuntimeHandle, int64_t v8ValueHandle) {
        JNIEnv* jniEnv = CurrentJNIEnv();
        RUNTIME_AND_VALUE_HANDLES_TO_OBJECTS_WITH_SCOPE(v8RuntimeHandle, v8ValueHandle);
        bool success = false;
        if (v8LocalValue->IsObject()) {
            Slot* slots = exchange.slots;
            auto v8LocalKey = Decode(v8Isolate, v8Context, slots[0]);
            auto v8LocalPrivateValue = Decode(v8Isolate, v8Context, slots[1]);
            if (v8LocalKey->IsString()) {
                auto v8LocalPrivateKey = v8::Private::ForApi(v8Isolate, v8LocalKey.As<v8::String>());
                auto v8MaybeBool = v8LocalValue.As<v8::Object>()->SetPrivate(v8Context, v8LocalPrivateKey, v8LocalPrivateValue);
                if (v8MaybeBool.IsNothing()) {
                    Javet::Exceptions::HandlePendingException(jniEnv, v8Runtime, v8Context);
                }
                success = v8MaybeBool.FromMaybe(false);
            }
        }
        ResetScratch();
        SetSlot(exchange.slots[0], V8ValueType::Boolean, success ? 1 : 0);
        return Finish(jniEnv);
    }
}
