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

import javafx.scene.Node;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Consumer;

/**
 * Parameter parsing shared by the extended operations.
 */
final class Params {

    private Params() {
    }

    static String req(Map<String, String> p, String name) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            throw new FxPilotException(400, Codes.BAD_REQUEST, "parameter '" + name + "' is required");
        }
        return v;
    }

    static String opt(Map<String, String> p, String name, String def) {
        String v = p.get(name);
        return v == null || v.isBlank() ? def : v;
    }

    static boolean bool(Map<String, String> p, String name) {
        return Boolean.parseBoolean(p.get(name));
    }

    static int intVal(Map<String, String> p, String name, int def) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        try {
            return Integer.parseInt(v.trim());
        } catch (NumberFormatException e) {
            throw new FxPilotException(400, Codes.INVALID_NUMBER, "'" + name + "' must be an integer");
        }
    }

    static double doubleVal(Map<String, String> p, String name, double def) {
        String v = p.get(name);
        if (v == null || v.isBlank()) {
            return def;
        }
        return finite(name, v);
    }

    /** Parses a finite number; NaN and Infinity pass every comparison check unnoticed, so they are rejected. */
    static double finite(String raw) {
        return finite("value", raw);
    }

    static double finite(String name, String raw) {
        double d;
        try {
            d = Double.parseDouble(raw.trim());
        } catch (NumberFormatException e) {
            throw new FxPilotException(400, Codes.INVALID_NUMBER, "'" + name + "' must be a number");
        }
        if (!Double.isFinite(d)) {
            throw new FxPilotException(400, Codes.INVALID_NUMBER, "'" + name + "' must be a finite number");
        }
        return d;
    }

    /** Integer within [min, max]; repeat counts outside the range would keep the FX thread busy for hours. */
    static int bounded(Map<String, String> p, String name, int def, int min, int max) {
        int v = intVal(p, name, def);
        if (v < min || v > max) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "'" + name + "' must be between " + min + " and " + max);
        }
        return v;
    }

    static Map<String, Object> action(Map<String, String> p, Consumer<Node> action) {
        return FxThread.action(req(p, "selector"), p.get("within"), p.get("nth"),
                bool(p, "requireVisible"), bool(p, "requireEnabled"), action);
    }

    static Node node(Map<String, String> p) {
        return Selectors.find(req(p, "selector"), p.get("within"), p.get("nth"));
    }

    static Map<String, Object> map(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i + 1 < kv.length; i += 2) {
            m.put(String.valueOf(kv[i]), kv[i + 1]);
        }
        return m;
    }
}
