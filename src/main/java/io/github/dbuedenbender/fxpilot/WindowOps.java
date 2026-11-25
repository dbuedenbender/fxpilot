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
import javafx.scene.control.Button;
import javafx.scene.control.ButtonBar;
import javafx.scene.control.ButtonBase;
import javafx.scene.control.ButtonType;
import javafx.scene.control.DialogPane;
import javafx.scene.control.Labeled;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Window and dialog operations.
 */
final class WindowOps {

    private WindowOps() {
    }

    static Map<String, Object> windowAction(Map<String, String> p) {
        return FxThread.interact(() -> {
            Window window = Selectors.window(p.get("window"));
            String action = Params.opt(p, "action", "focus");
            switch (action) {
                case "focus": window.requestFocus(); break;
                case "close":
                    Policy.requireDangerous("window action 'close'");
                    if (window instanceof Stage) {
                        ((Stage) window).close();
                    } else {
                        window.hide();
                    }
                    break;
                case "accept": clickDialogButton(window, true); break;
                case "dismiss": clickDialogButton(window, false); break;
                case "iconify": requireStage(window).setIconified(true); break;
                case "restore": requireStage(window).setIconified(false); break;
                case "maximize": requireStage(window).setMaximized(true); break;
                case "unmaximize": requireStage(window).setMaximized(false); break;
                case "move":
                    window.setX(Params.doubleVal(p, "x", window.getX()));
                    window.setY(Params.doubleVal(p, "y", window.getY()));
                    break;
                case "resize":
                    window.setWidth(Params.doubleVal(p, "width", window.getWidth()));
                    window.setHeight(Params.doubleVal(p, "height", window.getHeight()));
                    break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown window action '" + action + "'");
            }
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("status", "done");
            int index = Selectors.windows().indexOf(window);
            m.put("window", NodeInfo.windowInfo(Math.max(0, index), window));
            return m;
        });
    }

    /**
     * Fires the accepting or dismissing button of a dialog. Buttons are recognized by their role, so this
     * works in any language: the ButtonData of a DialogPane's button types (OK_DONE, YES, APPLY, FINISH or
     * CANCEL_CLOSE, NO), then default/cancel buttons, and only as a last resort English button texts.
     */
    private static void clickDialogButton(Window window, boolean accept) {
        Node root = window.getScene().getRoot();
        for (Node n : root.lookupAll(".dialog-pane")) {
            if (n instanceof DialogPane) {
                DialogPane pane = (DialogPane) n;
                for (ButtonType type : pane.getButtonTypes()) {
                    ButtonBar.ButtonData data = type.getButtonData();
                    boolean match = accept
                            ? data == ButtonBar.ButtonData.OK_DONE || data == ButtonBar.ButtonData.YES
                                    || data == ButtonBar.ButtonData.APPLY || data == ButtonBar.ButtonData.FINISH
                            : data == ButtonBar.ButtonData.CANCEL_CLOSE || data == ButtonBar.ButtonData.NO;
                    Node button = pane.lookupButton(type);
                    if (match && button instanceof ButtonBase) {
                        fire((ButtonBase) button);
                        return;
                    }
                }
            }
        }
        for (Node n : root.lookupAll(".button")) {
            if (n instanceof Button && (accept ? ((Button) n).isDefaultButton() : ((Button) n).isCancelButton())) {
                fire((ButtonBase) n);
                return;
            }
        }
        String[] labels = accept ? new String[]{"OK", "YES", "APPLY", "SAVE", "CLOSE"}
                : new String[]{"CANCEL", "NO", "ABORT", "CLOSE"};
        for (Node n : root.lookupAll(".button")) {
            if (n instanceof ButtonBase && n instanceof Labeled && ((Labeled) n).getText() != null) {
                for (String label : labels) {
                    if (label.equalsIgnoreCase(((Labeled) n).getText().trim())) {
                        fire((ButtonBase) n);
                        return;
                    }
                }
            }
        }
        throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "No " + (accept ? "accepting" : "dismissing")
                + " dialog button found (by ButtonData, default/cancel button or text)");
    }

    private static void fire(ButtonBase button) {
        DenyList.checkDenied("dialog button '" + (button instanceof Labeled ? ((Labeled) button).getText() : "") + "'", button);
        button.fire();
    }

    private static Stage requireStage(Window window) {
        if (!(window instanceof Stage)) {
            throw new FxPilotException(400, Codes.WINDOW_ACTION_FAILED, "Window is not a Stage");
        }
        return (Stage) window;
    }
}
