/*
 * Copyright 2026 FxPilot contributors
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
package io.github.dbuedenbender.fxpilot;

import java.lang.reflect.Method;

/**
 * Reflection helpers that keep optional JavaFX modules (web, media) optional.
 */
final class Reflect {

    private Reflect() {
    }

    static Object invokeOptional(Object target, String method) {
        try {
            Method m = target.getClass().getMethod(method);
            return m.invoke(target);
        } catch (ReflectiveOperationException | RuntimeException e) {
            return NoValue.INSTANCE;
        }
    }

    static final class NoValue {
        static final NoValue INSTANCE = new NoValue();
    }

    static Object invoke(Object target, String method, Object... args) {
        try {
            Method found = null;
            for (Method candidate : target.getClass().getMethods()) {
                if (candidate.getName().equals(method) && candidate.getParameterCount() == args.length) {
                    found = candidate;
                    break;
                }
            }
            if (found == null) {
                throw new FxPilotException(400, Codes.UNSUPPORTED_ACTION,
                        "Method '" + method + "' not found on " + target.getClass().getName());
            }
            return found.invoke(target, args);
        } catch (ReflectiveOperationException | RuntimeException e) {
            if (e instanceof FxPilotException) {
                throw (FxPilotException) e;
            }
            throw new FxPilotException(500, Codes.ACTION_FAILED, "Reflection call '" + method + "' failed: " + e);
        }
    }
}
