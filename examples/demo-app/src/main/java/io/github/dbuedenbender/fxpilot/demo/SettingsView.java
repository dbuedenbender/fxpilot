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
import javafx.geometry.Insets;
import javafx.scene.Node;
import javafx.scene.control.Accordion;
import javafx.scene.control.Button;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ColorPicker;
import javafx.scene.control.Hyperlink;
import javafx.scene.control.Label;
import javafx.scene.control.Pagination;
import javafx.scene.control.Slider;
import javafx.scene.control.TitledPane;
import javafx.scene.control.ToggleButton;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;

/** Settings tab: accordion, color picker, toggle, pagination and the "reset demo data" danger button. */
final class SettingsView {

    private static final String[] TIPS = {
            "Call fx_health first: it returns the notes of this app.",
            "fx_get_view lists the visible controls with their fx:id and ref.",
            "Every action writes to #statusLabel, so fx_assert can check it.",
            "Sync takes about 1.5 s: use fx_wait_for on #statusLabel.",
            "Start with javafx:run@safe to see fxpilot.deny in action."
    };

    private final ActivityLog log;
    private final Runnable resetData;

    SettingsView(ActivityLog log, Runnable resetData) {
        this.log = log;
        this.resetData = resetData;
    }

    Node build() {
        ChoiceBox<String> language = new ChoiceBox<>(FXCollections.observableArrayList("English", "Deutsch"));
        language.setId("languageChoice");
        language.setValue("English");
        language.valueProperty().addListener((obs, old, value) -> log.info("Language: " + value));
        ToggleButton autosave = new ToggleButton("Autosave");
        autosave.setId("autosaveToggle");
        autosave.setOnAction(e -> log.info("Autosave " + (autosave.isSelected() ? "on" : "off")));
        TitledPane general = new TitledPane("General", new VBox(8, language, autosave));
        general.setId("generalPane");

        ColorPicker accent = new ColorPicker(Color.web("#1565c0"));
        accent.setId("accentPicker");
        accent.setOnAction(e -> log.info("Accent color: " + accent.getValue()));
        Slider fontSize = new Slider(10, 20, 13);
        fontSize.setId("fontSizeSlider");
        fontSize.setShowTickLabels(true);
        fontSize.setMajorTickUnit(2);
        fontSize.valueProperty().addListener((obs, old, value) ->
                fontSize.getScene().getRoot().setStyle("-fx-font-size: " + Math.round(value.doubleValue()) + "px;"));
        TitledPane display = new TitledPane("Display", new VBox(8, new Label("Accent color"), accent,
                new Label("Font size"), fontSize));
        display.setId("displayPane");

        Hyperlink docs = new Hyperlink("FxPilot on GitHub");
        docs.setId("docsLink");
        docs.setOnAction(e -> log.info("Docs: https://github.com/dbuedenbender/fxpilot"));
        Button reset = new Button("Reset demo data");
        reset.setId("resetButton");
        reset.getStyleClass().add("danger");
        reset.setOnAction(e -> {
            resetData.run();
            log.info("Demo data reset");
        });
        TitledPane advanced = new TitledPane("Advanced", new VBox(8, docs, reset));
        advanced.setId("advancedPane");

        Accordion accordion = new Accordion(general, display, advanced);
        accordion.setId("settingsAccordion");
        accordion.setExpandedPane(general);

        Pagination tips = new Pagination(TIPS.length);
        tips.setId("tipsPagination");
        tips.setPageFactory(i -> {
            Label tip = new Label(TIPS[i]);
            tip.setId("tipLabel");
            tip.setWrapText(true);
            return tip;
        });
        tips.setPrefHeight(120);

        VBox box = new VBox(12, accordion, new Label("Tips"), tips);
        box.setPadding(new Insets(12));
        return box;
    }
}
