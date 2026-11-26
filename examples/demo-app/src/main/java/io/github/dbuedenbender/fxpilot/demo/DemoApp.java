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

import io.github.dbuedenbender.fxpilot.FxPilotServer;
import javafx.application.Application;
import javafx.application.Platform;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Scene;
import javafx.scene.control.Alert;
import javafx.scene.control.Menu;
import javafx.scene.control.MenuBar;
import javafx.scene.control.MenuItem;
import javafx.scene.control.SeparatorMenuItem;
import javafx.scene.control.Tab;
import javafx.scene.control.TabPane;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.BorderPane;
import javafx.scene.layout.HBox;
import javafx.stage.Stage;

/**
 * "Order Desk", the JavaFX demo for FxPilot. It has customers, orders, a dashboard and settings, so
 * nearly every FxPilot tool has something to work on, and a few destructive actions to try
 * {@code fxpilot.deny} and {@code fxpilot.readonly} on. All data is in memory.
 */
public final class DemoApp extends Application {

    static final String NOTES = "Order Desk demo; all data is in memory. Destructive actions: "
            + "#deleteAllButton and the menu item 'Delete all customers' (Ctrl+Shift+D) delete every customer, "
            + "#resetButton resets the demo data, 'Exit' closes the app. Every action writes its result to "
            + "#statusLabel. Sync (#syncButton) takes about 1.5 s; wait for 'Synced' with fx_wait_for.";

    private final ActivityLog log = new ActivityLog();
    private CustomerView customerView;
    private OrderView orderView;

    @Override
    public void start(Stage stage) {
        customerView = new CustomerView(log);
        orderView = new OrderView(log, customerView.customers());
        DashboardView dashboardView = new DashboardView(log, customerView.customers());
        SettingsView settingsView = new SettingsView(log, this::resetData);

        TabPane tabs = new TabPane(
                tab("Customers", "customersTab", customerView.build()),
                tab("Orders", "ordersTab", orderView.build()),
                tab("Dashboard", "dashboardTab", dashboardView.build()),
                tab("Settings", "settingsTab", settingsView.build()));
        tabs.setId("mainTabs");
        tabs.getSelectionModel().selectedItemProperty().addListener((obs, old, tab) -> log.info("Tab: " + tab.getText()));

        HBox statusBar = new HBox(8, log.statusLabel());
        statusBar.setId("statusBar");
        statusBar.setAlignment(Pos.CENTER_LEFT);
        statusBar.setPadding(new Insets(4, 12, 6, 12));

        BorderPane root = new BorderPane(tabs);
        root.setId("root");
        root.setTop(menuBar(stage));
        root.setBottom(statusBar);

        stage.setTitle("FxPilot Demo - Order Desk");
        stage.setScene(new Scene(root, 1000, 680));
        stage.show();

        FxPilotServer.setNotes(NOTES);
        FxPilotServer.startIfEnabled();
    }

    private static Tab tab(String text, String id, javafx.scene.Node content) {
        Tab tab = new Tab(text, content);
        tab.setId(id);
        tab.setClosable(false);
        return tab;
    }

    private MenuBar menuBar(Stage stage) {
        MenuItem newCustomer = item("New customer", "newCustomerItem", "Shortcut+N", customerView::clearForm);
        MenuItem save = item("Save", "saveItem", "Shortcut+S", customerView::save);
        Menu export = new Menu("Export");
        export.setId("exportMenu");
        export.getItems().addAll(
                item("CSV", "exportCsvItem", null, () -> log.info("Exported " + customerView.customers().size() + " customers as CSV")),
                item("PDF", "exportPdfItem", null, () -> log.info("Exported " + customerView.customers().size() + " customers as PDF")));
        MenuItem deleteAll = item("Delete all customers", "deleteAllItem", "Shortcut+Shift+D", customerView::deleteAll);
        deleteAll.getStyleClass().add("danger");
        MenuItem exit = item("Exit", "exitItem", null, Platform::exit);
        exit.getStyleClass().add("danger");
        Menu file = new Menu("File", null, newCustomer, save, export, new SeparatorMenuItem(), deleteAll,
                new SeparatorMenuItem(), exit);
        file.setId("fileMenu");

        Menu edit = new Menu("Edit", null,
                item("Clear form", "clearFormItem", null, customerView::clearForm),
                item("Sync now", "syncItem", "F5", customerView::sync));
        edit.setId("editMenu");

        Menu help = new Menu("Help", null, item("About", "aboutItem", "F1", () -> about(stage)));
        help.setId("helpMenu");

        MenuBar bar = new MenuBar(file, edit, help);
        bar.setId("menuBar");
        return bar;
    }

    private static MenuItem item(String text, String id, String accelerator, Runnable action) {
        MenuItem item = new MenuItem(text);
        item.setId(id);
        if (accelerator != null) {
            item.setAccelerator(KeyCombination.keyCombination(accelerator));
        }
        item.setOnAction(e -> action.run());
        return item;
    }

    private void about(Stage owner) {
        Alert about = new Alert(Alert.AlertType.INFORMATION, "Order Desk demo for FxPilot.\n" + NOTES);
        about.initOwner(owner);
        about.setTitle("About FxPilot Demo");
        about.setHeaderText("FxPilot Demo - Order Desk");
        about.showAndWait();
        log.info("About closed");
    }

    private void resetData() {
        customerView.resetData();
        orderView.resetData();
    }

    @Override
    public void stop() {
        FxPilotServer.stop();
    }

    public static void main(String[] args) {
        Startup.silenceClasspathNotice();
        launch(args);
    }
}
