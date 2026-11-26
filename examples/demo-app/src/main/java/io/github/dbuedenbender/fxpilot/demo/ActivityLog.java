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
package io.github.dbuedenbender.fxpilot.demo;

import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.scene.control.Label;

/**
 * Status line plus activity list. Every action of the demo writes here, so FxPilot can check the
 * result of a step with {@code fx_assert(selector="statusLabel", text=...)} or {@code fx_wait_for}.
 */
final class ActivityLog {

    private final Label status = new Label("Ready");
    private final ObservableList<String> entries = FXCollections.observableArrayList();

    ActivityLog() {
        status.setId("statusLabel");
    }

    Label statusLabel() {
        return status;
    }

    ObservableList<String> entries() {
        return entries;
    }

    void info(String message) {
        status.setText(message);
        status.setStyle("");
        entries.add(0, message);
    }

    void error(String message) {
        status.setText("Error: " + message);
        status.setStyle("-fx-text-fill: #c62828;");
        entries.add(0, "Error: " + message);
    }
}
