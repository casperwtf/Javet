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

#pragma once

#include <jni.h>
#include <cstdint>
#include "javet_v8.h"

/*
 * FFM fast path used by V8NativeFfm (Java 22+, multi-release jar).
 *
 * Values cross the boundary as 16-byte tagged slots in a per-thread exchange
 * instead of JNI objects, so a call or a callback costs one transition and no
 * JNI upcalls per value. A slot is {int32 tag, int32 length, int64 payload}:
 * the low 16 bits of tag are a V8ValueType id, the high 16 bits are flags.
 *
 * Native -> Java strings are written to the exchange scratch buffer and the
 * payload is an offset into it. Java -> native strings are UTF-16 in the
 * buffer javet_ffm_reserve() hands out and the payload is an absolute address.
 * Reference values carry a V8PersistentValue* the receiver owns.
 *
 * All of it is native thread_local memory, so the Java side keeps no per-thread
 * state that would pin its class loader after a hot swap.
 *
 * Every slot must be consumed before control leaves the receiving side again:
 * decode immediately, encode last. That keeps one exchange per thread safe
 * under re-entrant calls.
 */
namespace Javet {
    namespace Callback {
        class JavetCallbackContextReference;
    }

    namespace FFM {
        constexpr int32_t ABI_VERSION = 3;
        constexpr int32_t SLOT_COUNT = 256;

        constexpr int32_t STATUS_OK = 0;
        constexpr int32_t STATUS_THROWN = 1;
        // The JNI method returns Java null (objectInvoke on a missing or non-function property).
        constexpr int32_t STATUS_NULL = 2;

        constexpr int32_t TYPE_EXTERNAL = 0xFFFF;
        constexpr int32_t FLAG_ONE_BYTE = 1;
        constexpr int32_t FLAG_NEGATIVE = 1;

        constexpr int64_t RELEASED_BY_JAVA = -1;

        constexpr int32_t CALLBACK_THIS = 1;
        constexpr int32_t CALLBACK_RESULT = 2;

        struct Slot {
            int32_t tag;
            int32_t length;
            int64_t payload;
        };

        // The part Java reads and writes. Java finds it through javet_ffm_exchange().
        struct Exchange {
            uint8_t* scratch;
            int64_t scratchLength;
            // A callback result Java has closed but native code still owns (see javet_ffm_remove_reference).
            int64_t releasingHandle;
            // Callback argument count on upcalls; slots written by batch gets.
            int32_t argc;
            int32_t flags;
            Slot slots[SLOT_COUNT];
        };

        using CallbackUpcall = int64_t(*)(int64_t v8RuntimeHandle, int64_t contextHandle);

        bool TryCallFunction(
            Javet::Callback::JavetCallbackContextReference* callbackContextReference,
            const v8::FunctionCallbackInfo<v8::Value>& args) noexcept;
    }
}

extern "C" {
    JNIEXPORT int32_t JNICALL javet_ffm_version();
    JNIEXPORT void* JNICALL javet_ffm_exchange();
    JNIEXPORT void* JNICALL javet_ffm_reserve(int64_t bytes);
    JNIEXPORT void JNICALL javet_ffm_set_callback(void* upcall);
    JNIEXPORT int32_t JNICALL javet_ffm_function_call(int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t argc, int32_t resultRequired);
    JNIEXPORT int32_t JNICALL javet_ffm_object_create(int64_t v8RuntimeHandle);
    JNIEXPORT int32_t JNICALL javet_ffm_object_get(int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t v8ValueType, int32_t primitiveOnly);
    JNIEXPORT int32_t JNICALL javet_ffm_object_set(int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t pairCount);
    JNIEXPORT void JNICALL javet_ffm_remove_reference(int64_t v8RuntimeHandle, int64_t referenceHandle);
    JNIEXPORT int32_t JNICALL javet_ffm_batch_array_get(int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t v8ValueType, int32_t startIndex, int32_t count);
    JNIEXPORT int32_t JNICALL javet_ffm_batch_object_get(int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t count);
    JNIEXPORT int32_t JNICALL javet_ffm_object_invoke(int64_t v8RuntimeHandle, int64_t v8ValueHandle, int32_t v8ValueType, int32_t argc, int32_t resultRequired);
    JNIEXPORT int32_t JNICALL javet_ffm_object_set_private_property(int64_t v8RuntimeHandle, int64_t v8ValueHandle);
}
