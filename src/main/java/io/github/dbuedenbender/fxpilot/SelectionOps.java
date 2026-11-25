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

import javafx.event.Event;
import javafx.scene.Node;
import javafx.scene.control.ListView;
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.PasswordField;
import javafx.scene.control.TableView;
import javafx.scene.control.TextInputControl;
import javafx.scene.control.TreeTableView;
import javafx.scene.control.TreeView;
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;

/**
 * Text editing and multi-selection operations.
 */
final class SelectionOps {

    private SelectionOps() {
    }

    static void textAction(Node node, String action, String value, int start, int end) {
        TextInputControl text = Actions.textInput(node);
        String name = action == null || action.isBlank() ? "clear" : action;
        if (node instanceof PasswordField && ("copy".equalsIgnoreCase(name) || "cut".equalsIgnoreCase(name))) {
            throw new FxPilotException(403, Codes.NODE_DENIED, "Copy/cut is disabled for PasswordField");
        }
        switch (name) {
            case "clear": text.clear(); break;
            case "append": text.appendText(value == null ? "" : value); break;
            case "selectAll": text.selectAll(); break;
            case "selectRange": text.selectRange(Math.max(0, start), Math.max(start, end < 0 ? text.getLength() : end)); break;
            case "caret": text.positionCaret(Math.max(0, start)); break;
            case "replace":
                int to = end < 0 ? text.getLength() : end;
                text.replaceText(Math.max(0, start), Math.max(start, to), value == null ? "" : value);
                break;
            case "copy": text.copy(); break;
            case "paste": text.paste(); break;
            case "cut": text.cut(); break;
            default:
                throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown text action '" + name + "'");
        }
        Event.fireEvent(text, new KeyEvent(KeyEvent.KEY_RELEASED, KeyEvent.CHAR_UNDEFINED, "", KeyCode.END,
                false, false, false, false));
    }

    static void selectRows(Node node, String indices) {
        if (indices == null || indices.isBlank()) {
            throw new FxPilotException(400, Codes.BAD_REQUEST, "indices is required");
        }
        MultipleSelectionModel<?> model = selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " has no selection model");
        }
        model.setSelectionMode(javafx.scene.control.SelectionMode.MULTIPLE);
        model.clearSelection();
        for (String part : indices.split("[,;\\s]+")) {
            if (part.isBlank()) {
                continue;
            }
            try {
                int index = Integer.parseInt(part.trim());
                if (index >= 0) {
                    model.select(index);
                }
            } catch (NumberFormatException e) {
                throw new FxPilotException(400, Codes.INVALID_NUMBER, "'" + part + "' is not a row index");
            }
        }
    }

    static void selectAll(Node node) {
        MultipleSelectionModel<?> model = selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " has no selection model");
        }
        model.selectAll();
    }

    static void clearSelection(Node node) {
        MultipleSelectionModel<?> model = selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " has no selection model");
        }
        model.clearSelection();
    }

    static MultipleSelectionModel<?> selectionModel(Node node) {
        if (node instanceof ListView) {
            return ((ListView<?>) node).getSelectionModel();
        }
        if (node instanceof TableView) {
            return ((TableView<?>) node).getSelectionModel();
        }
        if (node instanceof TreeView) {
            return ((TreeView<?>) node).getSelectionModel();
        }
        if (node instanceof TreeTableView) {
            return ((TreeTableView<?>) node).getSelectionModel();
        }
        return null;
    }
}
