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

import com.caoccao.javet.BaseTestJavetRuntime;
import com.caoccao.javet.exceptions.JavetException;
import com.caoccao.javet.exceptions.JavetExecutionException;
import com.caoccao.javet.interop.callback.IJavetDirectCallable;
import com.caoccao.javet.interop.callback.JavetCallbackContext;
import com.caoccao.javet.interop.callback.JavetCallbackType;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.primitive.V8ValueBigInteger;
import com.caoccao.javet.values.primitive.V8ValueString;
import com.caoccao.javet.values.reference.V8ValueArray;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import com.caoccao.javet.values.reference.V8ValueTypedArray;
import org.junit.jupiter.api.Test;

import java.math.BigInteger;
import java.util.ArrayList;
import java.util.List;
import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Covers the values and failure paths of V8NativeFfm. The rest of the suite runs through it too
 * whenever the tests run on Java 25+, because the Java 25 classes are on the test runtime classpath.
 */
public class TestV8NativeFfm extends BaseTestJavetRuntime {
    private static final String UNICODE = "héllo wörld ✓ 𝄞";

    private static boolean isFfm(V8Runtime v8Runtime) {
        return v8Runtime.v8Native.getClass().getSimpleName().equals("V8NativeFfm");
    }

    @Test
    public void testCallbackArgumentsAndResults() throws JavetException {
        IJavetDirectCallable.NoThisAndResult<Exception> echo = args -> {
            try (V8ValueArray array = v8Runtime.createV8ValueArray()) {
                for (V8Value arg : args) {
                    array.push(arg);
                }
                return array.toClone();
            }
        };
        try (V8ValueFunction function = v8Runtime.createV8ValueFunction(
                new JavetCallbackContext("echo", JavetCallbackType.DirectCallNoThisAndResult, echo))) {
            v8Runtime.getGlobalObject().set("echo", function);
        }
        try {
            String script = "const d = new Date(1700000000000);\n"
                    + "const r = echo(1, 1.5, 'abc', '" + UNICODE + "', true, null, undefined, 12n, 2n ** 80n,"
                    + " d, {a: 1}, [1, 2], new Uint8Array([1, 2]), new ArrayBuffer(4), Symbol('s'), new Map());\n"
                    + "JSON.stringify([r[0], r[1], r[2], r[3], r[4], r[5], r[6] === undefined, String(r[7]),"
                    + " String(r[8]), r[9].getTime(), r[10].a, r[11][1], r[12][1], r[13].byteLength,"
                    + " typeof r[14], r[15] instanceof Map]);";
            assertEquals(
                    "[1,1.5,\"abc\",\"" + UNICODE + "\",true,null,true,\"12\",\"1208925819614629174706176\","
                            + "1700000000000,1,2,2,4,\"symbol\",true]",
                    v8Runtime.getExecutor(script).executeString());
        } finally {
            v8Runtime.getGlobalObject().delete("echo");
            v8Runtime.lowMemoryNotification();
        }
    }

    @Test
    public void testCallbackExceptionReachesJavaScriptAndJava() throws JavetException {
        IJavetDirectCallable.NoThisAndNoResult<Exception> fail = args -> {
            throw new IllegalStateException("boom from java");
        };
        try (V8ValueFunction function = v8Runtime.createV8ValueFunction(
                new JavetCallbackContext("fail", JavetCallbackType.DirectCallNoThisAndNoResult, fail))) {
            v8Runtime.getGlobalObject().set("fail", function);
        }
        try {
            assertEquals("boom from java", v8Runtime.getExecutor(
                    "(() => { try { fail(); fail(); return 'no'; } catch (e) { return e.message; } })()").executeString());
            try (V8ValueFunction caller = v8Runtime.getExecutor("() => fail()").execute()) {
                JavetExecutionException exception = assertThrows(
                        JavetExecutionException.class, () -> caller.callVoid(null));
                assertTrue(exception.getMessage().contains("boom from java"));
                assertInstanceOf(IllegalStateException.class, exception.getCause());
            }
        } finally {
            v8Runtime.getGlobalObject().delete("fail");
            v8Runtime.lowMemoryNotification();
        }
    }

    @Test
    public void testCallbackReturnsLiveReference() throws JavetException {
        try (V8ValueObject kept = v8Runtime.createV8ValueObject()) {
            kept.set("x", 7);
            IJavetDirectCallable.NoThisAndResult<Exception> fresh = args -> {
                V8ValueObject object = v8Runtime.createV8ValueObject();
                object.set("y", 8);
                return object;
            };
            IJavetDirectCallable.NoThisAndResult<Exception> same = args -> kept.toClone();
            try (V8ValueFunction freshFunction = v8Runtime.createV8ValueFunction(
                    new JavetCallbackContext("fresh", JavetCallbackType.DirectCallNoThisAndResult, fresh));
                 V8ValueFunction sameFunction = v8Runtime.createV8ValueFunction(
                         new JavetCallbackContext("same", JavetCallbackType.DirectCallNoThisAndResult, same))) {
                v8Runtime.getGlobalObject().set("fresh", freshFunction);
                v8Runtime.getGlobalObject().set("same", sameFunction);
            }
            try {
                int before = v8Runtime.getReferenceCount();
                assertEquals(8 * 1000 + 7 * 1000, v8Runtime.getExecutor(
                        "let s = 0; for (let i = 0; i < 1000; i++) s += fresh().y + same().x; s").executeInteger());
                assertEquals(before, v8Runtime.getReferenceCount(), "callback results must not leak");
                assertEquals(7, kept.getInteger("x"));
            } finally {
                v8Runtime.getGlobalObject().delete("fresh");
                v8Runtime.getGlobalObject().delete("same");
            }
        }
        v8Runtime.lowMemoryNotification();
    }

    @Test
    public void testFunctionCallValues() throws JavetException {
        try (V8ValueFunction function = v8Runtime.getExecutor(
                "(...a) => a.map(v => v === null ? 'null' : typeof v === 'bigint' ? v + 'n'"
                        + " : v instanceof Date ? v.getTime() : typeof v === 'object' ? JSON.stringify(v) : String(v)).join('|')")
                .execute();
             V8ValueObject object = v8Runtime.createV8ValueObject()) {
            object.set("k", "v");
            ZonedDateTime time = ZonedDateTime.parse("2026-10-02T00:00:00Z");
            String result = function.callString(null,
                    1, 2.5, "x", UNICODE, true, null, 5L, new BigInteger("-1208925819614629174706176"), time, object);
            assertEquals("1|2.5|x|" + UNICODE + "|true|null|5n|-1208925819614629174706176n|"
                    + time.toInstant().toEpochMilli() + "|{\"k\":\"v\"}", result);
        }
    }

    @Test
    public void testFunctionCallThrows() throws JavetException {
        try (V8ValueFunction function = v8Runtime.getExecutor("(m) => { throw new TypeError(m); }").execute()) {
            JavetExecutionException exception = assertThrows(
                    JavetExecutionException.class, () -> function.callVoid(null, "bad thing"));
            assertEquals("TypeError: bad thing", exception.getMessage());
        }
    }

    @Test
    public void testManyArgumentsFallBackToJni() throws JavetException {
        try (V8ValueFunction function = v8Runtime.getExecutor("(...a) => a.length").execute()) {
            Object[] values = new Object[1000];
            for (int i = 0; i < values.length; ++i) {
                values[i] = i;
            }
            assertEquals(1000, function.callInteger(null, values));
        }
    }

    @Test
    public void testObjectGetAndSet() throws JavetException {
        try (V8ValueObject object = v8Runtime.getExecutor(
                "({i: 1, d: 1.5, s: 'str', u: '" + UNICODE + "', b: true, l: 3n, big: 2n ** 70n,"
                        + " n: new Number(4), o: {x: 1}, t: new Uint16Array(2), get g() { return 'getter'; }})").execute()) {
            assertEquals(1, object.getInteger("i"));
            assertEquals(1.5, object.getDouble("d"));
            assertEquals(1.0, object.getDouble("i"));
            assertEquals(4.0, object.getDouble("n"));
            assertNull(object.getInteger("d"));
            assertEquals("str", object.getString("s"));
            assertEquals(UNICODE, object.getString("u"));
            assertNull(object.getString("i"));
            assertTrue(object.getBoolean("b"));
            assertEquals(3L, object.getLong("l"));
            assertEquals("getter", object.getString("g"));
            try (V8ValueBigInteger value = object.get("big")) {
                assertEquals(new BigInteger("1180591620717411303424"), value.getValue());
            }
            try (V8ValueTypedArray typedArray = object.get("t")) {
                assertEquals(2, typedArray.getLength());
            }
            try (V8ValueObject inner = object.get("o")) {
                assertEquals(1, inner.getInteger("x"));
            }
            try (V8ValueString value = object.get("u")) {
                assertEquals(UNICODE, value.getValue());
            }
            assertTrue(object.set("a", 1, "b", "two", 3, true));
            try (V8ValueFunction json = v8Runtime.getExecutor(
                    "o => JSON.stringify({3: o[3], a: o.a, b: o.b})").execute()) {
                assertEquals("{\"3\":true,\"a\":1,\"b\":\"two\"}", json.callString(null, object));
            }
        }
    }

    @Test
    public void testBatchArrayGetAcrossChunks() throws JavetException {
        try (V8ValueArray array = v8Runtime.getExecutor(
                "Array.from({length: 700}, (_, i) => i % 3 === 0 ? 'v' + i : i % 3 === 1 ? i : {i})").execute()) {
            assertEquals(700, array.getLength());
            V8Value[] values = new V8Value[600];
            assertEquals(600, array.batchGet(values, 50, 700));
            for (int i = 0; i < values.length; ++i) {
                int source = i + 50;
                if (source % 3 == 0) {
                    assertEquals("v" + source, ((V8ValueString) values[i]).getValue());
                } else if (source % 3 == 1) {
                    assertEquals(source, ((com.caoccao.javet.values.primitive.V8ValueInteger) values[i]).getValue());
                } else {
                    assertEquals(source, ((V8ValueObject) values[i]).getInteger("i"));
                }
            }
            com.caoccao.javet.utils.JavetResourceUtils.safeClose((Object[]) values);
            V8Value[] tail = new V8Value[10];
            assertEquals(3, array.batchGet(tail, 697, 1000));
            com.caoccao.javet.utils.JavetResourceUtils.safeClose((Object[]) tail);
        }
        try (V8ValueTypedArray typedArray = v8Runtime.getExecutor("new Int32Array([5, 6, 7])").execute()) {
            V8Value[] values = new V8Value[3];
            assertEquals(3, v8Runtime.v8Native.batchArrayGet(
                    v8Runtime.getHandle(), typedArray.getHandle(), typedArray.getType().getId(), values, 0, 3));
            assertEquals(7, ((com.caoccao.javet.values.primitive.V8ValueInteger) values[2]).getValue());
        }
    }

    @Test
    public void testBatchObjectGetWithReentrantGetter() throws JavetException {
        IJavetDirectCallable.NoThisAndResult<Exception> nested = args -> v8Runtime.createV8ValueString("nested-" + args.length);
        try (V8ValueFunction function = v8Runtime.createV8ValueFunction(
                new JavetCallbackContext("nested", JavetCallbackType.DirectCallNoThisAndResult, nested))) {
            v8Runtime.getGlobalObject().set("nested", function);
        }
        try (V8ValueObject object = v8Runtime.getExecutor(
                "({a: 1, get b() { return nested(1, 2, 3, 'x', {y: 1}); }, c: 'three', 4: 'four'})").execute()) {
            List<String> seen = new ArrayList<>();
            object.forEach((V8Value key, V8Value value) -> seen.add(key + "=" + value));
            assertEquals(List.of("4=four", "a=1", "b=nested-5", "c=three"), seen);
        } finally {
            v8Runtime.getGlobalObject().delete("nested");
            v8Runtime.lowMemoryNotification();
        }
    }

    @Test
    public void testObjectInvokeAndPrivateProperty() throws JavetException {
        try (V8ValueObject object = v8Runtime.getExecutor(
                "({n: 2, twice(x) { return x * this.n; }, fail(m) { throw new Error(m); }, notFunction: 1})").execute()) {
            assertEquals(42, object.invokeInteger("twice", 21));
            JavetExecutionException exception = assertThrows(
                    JavetExecutionException.class, () -> object.invokeVoid("fail", "invoke failed"));
            assertEquals("Error: invoke failed", exception.getMessage());
            assertNull(v8Runtime.v8Native.objectInvoke(v8Runtime.getHandle(), object.getHandle(),
                    object.getType().getId(), "missing", true, new Object[0], new int[0]));
            assertNull(v8Runtime.v8Native.objectInvoke(v8Runtime.getHandle(), object.getHandle(),
                    object.getType().getId(), "notFunction", true, new Object[0], new int[0]));
            assertTrue(object.setPrivateProperty("secret", UNICODE));
            assertEquals(UNICODE, object.getPrivatePropertyString("secret"));
            assertTrue(object.setPrivateProperty("count", 9));
            assertEquals(9, object.getPrivatePropertyInteger("count"));
        }
    }

    @Test
    public void testUsesFfmOnJava25() {
        String version = System.getProperty("java.specification.version");
        if (!version.contains(".") && Integer.parseInt(version) >= 25 && !"false".equals(System.getProperty("javet.ffm"))) {
            assertTrue(isFfm(v8Runtime), "Java " + version + " should load V8NativeFfm");
        }
    }
}
