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
import javafx.scene.control.PasswordField;
import javafx.scene.paint.Color;

import java.lang.reflect.Method;
import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;

/**
 * Generic JavaFX bean property access.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class PropertyOps {

    private PropertyOps() {
    }

    static Map<String, Object> propertyGet(Map<String, String> p) {
        return FxThread.query(() -> {
            Node node = Params.node(p);
            String name = Params.req(p, "name");
            Object value = readProperty(node, name);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("name", name);
            m.put("value", value instanceof Node ? ((Node) value).getId() : value);
            m.put("type", value == null ? null : value.getClass().getName());
            return m;
        });
    }

    static Map<String, Object> propertySet(Map<String, String> p) {
        return FxThread.action(Params.req(p, "selector"), p.get("within"), p.get("nth"),
                Params.bool(p, "requireVisible"), Params.bool(p, "requireEnabled"), node -> {
                    writeProperty(node, Params.req(p, "name"), p.get("value"));
                });
    }

    /**
     * Properties of a PasswordField that do not depend on its text. Everything else (text, characters,
     * selectedText, length, selection, caret position, custom values in getProperties() ...) could reveal the
     * password or parts of it, so it is refused instead of filtered.
     */
    private static final Set<String> PASSWORD_FIELD_READABLE = Set.of(
            "id", "promptText", "editable", "disable", "disabled", "visible", "managed", "focused", "focusTraversable",
            "opacity", "style", "styleClass", "width", "height", "layoutX", "layoutY", "prefColumnCount",
            "alignment", "font", "tooltip", "accessibleText", "accessibleHelp", "accessibleRole");

    private static Object readProperty(Node node, String name) {
        if (node instanceof PasswordField && !PASSWORD_FIELD_READABLE.contains(name)) {
            throw new FxPilotException(403, Codes.NODE_DENIED, "Property '" + name + "' of a PasswordField is not readable;"
                    + " readable are " + new TreeSet<>(PASSWORD_FIELD_READABLE));
        }
        return readRaw(node, name);
    }

    private static Object readRaw(Node node, String name) {
        if (node.getProperties().containsKey(name)) {
            return node.getProperties().get(name);
        }
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (String prefix : new String[]{"get", "is"}) {
            Object value = Reflect.invokeOptional(node, prefix + suffix);
            if (value != Reflect.NoValue.INSTANCE) {
                return value;
            }
        }
        Object property = Reflect.invokeOptional(node, name + "Property");
        if (property instanceof javafx.beans.value.ObservableValue) {
            return ((javafx.beans.value.ObservableValue<?>) property).getValue();
        }
        checkAccessible(node, name, suffix);
        throw new FxPilotException(404, Codes.PROPERTY_NOT_FOUND, "Property '" + name + "' not found on " + Selectors.typeName(node));
    }

    /**
     * In a modular app, a getter of the app's own class exists but cannot be called when its package is not
     * exported to FxPilot. Report that instead of "not found", with the line the app needs.
     */
    private static void checkAccessible(Node node, String name, String suffix) {
        for (Method m : node.getClass().getMethods()) {
            boolean getter = m.getParameterCount() == 0 && (m.getName().equals("get" + suffix)
                    || m.getName().equals("is" + suffix) || m.getName().equals(name + "Property"));
            Class<?> owner = m.getDeclaringClass();
            if (getter && !owner.getModule().isExported(owner.getPackageName(), PropertyOps.class.getModule())) {
                throw new FxPilotException(403, Codes.PROPERTY_NOT_ACCESSIBLE, "Property '" + name + "' of "
                        + owner.getName() + " is not accessible: module " + owner.getModule().getName()
                        + " does not export " + owner.getPackageName() + ". Add 'exports " + owner.getPackageName()
                        + " to io.github.dbuedenbender.fxpilot;' to its module-info.java.");
            }
        }
    }

    private static void writeProperty(Node node, String name, String value) {
        String suffix = Character.toUpperCase(name.charAt(0)) + name.substring(1);
        for (Method method : node.getClass().getMethods()) {
            if (method.getName().equals("set" + suffix) && method.getParameterCount() == 1) {
                try {
                    method.invoke(node, convert(value, method.getParameterTypes()[0]));
                    return;
                } catch (ReflectiveOperationException | RuntimeException e) {
                    throw new FxPilotException(400, Codes.INVALID_VALUE, "Cannot set property '" + name + "': " + e);
                }
            }
        }
        node.getProperties().put(name, value);
    }

    private static Object convert(String value, Class<?> type) {
        if (type == String.class) {
            return value;
        }
        if (type == boolean.class || type == Boolean.class) {
            return Boolean.parseBoolean(value);
        }
        if (type == int.class || type == Integer.class) {
            return Integer.parseInt(value);
        }
        if (type == long.class || type == Long.class) {
            return Long.parseLong(value);
        }
        if (type == double.class || type == Double.class) {
            return Params.finite(value);
        }
        if (type == float.class || type == Float.class) {
            return (float) Params.finite(value);
        }
        if (type == LocalDate.class) {
            return LocalDate.parse(value);
        }
        if (type == Color.class) {
            return Color.web(value);
        }
        if (type.isEnum()) {
            return Enum.valueOf((Class<? extends Enum>) type, value);
        }
        return value;
    }
}
