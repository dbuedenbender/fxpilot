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

import javafx.application.Platform;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.scene.Parent;
import javafx.scene.Scene;
import javafx.scene.canvas.Canvas;
import javafx.scene.chart.BarChart;
import javafx.scene.chart.CategoryAxis;
import javafx.scene.chart.NumberAxis;
import javafx.scene.chart.XYChart;
import javafx.scene.control.*;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.KeyCombination;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.scene.paint.Color;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Headless JavaFX fixture for the endpoint tests: one window with the controls FxPilot supports.
 * {@link #show()} rebuilds the scene, so every test starts from the same state.
 */
public final class TestUi {

    public static final String TITLE = "FxPilot Test";
    private static boolean started;

    private TestUi() {
    }

    public static synchronized void startToolkit() throws Exception {
        if (started) {
            return;
        }
        // The tests load JavaFX from the class path so that Monocle works; JavaFX logs an
        // "Unsupported JavaFX configuration" warning for that once at startup. Tests run headless
        // on purpose, so drop just that message instead of the whole logger.
        Logger.getLogger("javafx").setFilter(record ->
                !String.valueOf(record.getMessage()).startsWith("Unsupported JavaFX configuration"));
        CompletableFuture<Void> up = new CompletableFuture<>();
        Platform.startup(() -> up.complete(null));
        up.get(20, TimeUnit.SECONDS);
        Platform.setImplicitExit(false);
        started = true;
    }

    /** Closes all windows and shows a fresh main window. */
    public static void show() throws Exception {
        startToolkit();
        onFx(() -> {
            for (Window w : new ArrayList<>(Window.getWindows())) {
                w.hide();
            }
            Stage stage = new Stage();
            stage.setTitle(TITLE);
            stage.setScene(new Scene(build(), 1000, 1100));
            stage.show();
            stage.requestFocus();
        });
    }

    public static void onFx(Runnable r) throws Exception {
        CompletableFuture<Void> done = new CompletableFuture<>();
        Platform.runLater(() -> {
            try {
                r.run();
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        done.get(20, TimeUnit.SECONDS);
    }

    private static void showConfirm(Label status) {
        Stage d = new Stage();
        d.initModality(Modality.APPLICATION_MODAL);
        d.setTitle("Confirm");
        Button close = new Button("Close");
        close.setId("closeDialog");
        close.setOnAction(x -> d.close());
        d.setScene(new Scene(new VBox(new Label("Really?"), close), 200, 100));
        d.showAndWait();
        status.setText("Dialog closed");
    }

    /** Containers whose children are skin-wrapped, a MenuButton, an editable table and a non-English dialog. */
    private static Parent extras(Label status) {
        Button toolButton = new Button("Tool");
        toolButton.setId("toolButton");
        toolButton.setOnAction(e -> status.setText("tool"));
        ToolBar toolBar = new ToolBar(toolButton);
        toolBar.setId("toolBar");

        Button scrollButton = new Button("Scrolled");
        scrollButton.setId("scrollButton");
        scrollButton.setOnAction(e -> status.setText("scrolled"));
        ScrollPane scroll = new ScrollPane(new VBox(scrollButton));
        scroll.setId("scrollPane");
        scroll.setPrefSize(120, 60);

        MenuItem nuke = new MenuItem("Nuke");
        nuke.setId("nuke");
        nuke.setAccelerator(KeyCombination.keyCombination("Ctrl+D"));
        nuke.setOnAction(e -> status.setText("nuked"));
        MenuButton actions = new MenuButton("Actions", null, nuke);
        actions.setId("actionsMenu");

        TableView<String[]> notes = new TableView<>();
        notes.setId("notesTable");
        notes.setEditable(true);
        TableColumn<String[], String> note = new TableColumn<>("Note");
        note.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()[0]));
        note.setCellFactory(javafx.scene.control.cell.TextFieldTableCell.forTableColumn());
        note.setOnEditCommit(e -> {
            e.getRowValue()[0] = e.getNewValue();
            status.setText("note " + e.getNewValue());
        });
        notes.getColumns().add(note);
        notes.getItems().add(new String[]{"old"});
        notes.setPrefHeight(90);

        Button german = new Button("German dialog");
        german.setId("openGerman");
        german.setOnAction(e -> {
            Dialog<ButtonType> d = new Dialog<>();
            d.setTitle("Frage");
            ButtonType apply = new ButtonType("Übernehmen", ButtonBar.ButtonData.OK_DONE);
            ButtonType cancel = new ButtonType("Abbrechen", ButtonBar.ButtonData.CANCEL_CLOSE);
            d.getDialogPane().getButtonTypes().addAll(apply, cancel);
            status.setText(d.showAndWait().map(b -> b == apply ? "german ok" : "german cancel").orElse("german none"));
        });
        return new HBox(toolBar, scroll, actions, notes, german);
    }

    static Parent build() {
        Label status = new Label("");
        status.setId("status");

        Label nameLabel = new Label("Name");
        TextField name = new TextField();
        name.setId("nameField");
        nameLabel.setLabelFor(name);
        MenuItem shout = new MenuItem("Shout");
        shout.setOnAction(e -> status.setText("Shouted"));
        MenuItem wipeField = new MenuItem("Wipe");
        wipeField.setOnAction(e -> status.setText("Field wiped"));
        name.setContextMenu(new ContextMenu(shout, wipeField));

        PasswordField password = new PasswordField();
        password.setId("password");
        password.setText("geheim");

        Button ok = new Button("OK");
        ok.setId("okButton");
        ok.setOnAction(e -> status.setText("Saved " + name.getText()));
        ok.setTooltip(new Tooltip("Saves the form"));

        Button delete = new Button("Delete all");
        delete.setId("deleteAll");
        delete.getStyleClass().add("danger");
        delete.setOnAction(e -> status.setText("Deleted"));
        delete.setDefaultButton(true);
        // ALT+P fires it through the scene's mnemonics, whatever node has the focus
        Button purge = new Button("_Purge");
        purge.setId("purge");
        purge.setMnemonicParsing(true);
        purge.setOnAction(e -> status.setText("Purged"));

        Button dupA = new Button("Dup");
        dupA.setId("dupA");
        dupA.setOnAction(e -> status.setText("dupA"));
        Button dupB = new Button("Dup");
        dupB.setId("dupB");
        dupB.setOnAction(e -> status.setText("dupB"));

        Button hidden = new Button("Hidden");
        hidden.setId("hiddenButton");
        hidden.setVisible(false);

        Button disabled = new Button("Disabled");
        disabled.setId("disabledButton");
        disabled.setDisable(true);

        Button dialog = new Button("Open dialog");
        dialog.setId("openDialog");
        dialog.setOnAction(e -> showConfirm(status));

        CheckBox agree = new CheckBox("Agree");
        agree.setId("agree");
        agree.setOnAction(e -> status.setText("agree=" + agree.isSelected()));

        ComboBox<String> country = new ComboBox<>(FXCollections.observableArrayList("DE", "CH", "AT"));
        country.setId("country");

        ListView<String> list = new ListView<>(FXCollections.observableArrayList("alpha", "beta", "gamma"));
        list.setId("list");
        list.setPrefHeight(80);

        TableView<String[]> table = new TableView<>();
        table.setId("customers");
        TableColumn<String[], String> colName = new TableColumn<>("Name");
        colName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()[0]));
        TableColumn<String[], String> colCountry = new TableColumn<>("Country");
        colCountry.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()[1]));
        table.getColumns().add(colName);
        table.getColumns().add(colCountry);
        table.getItems().addAll(new String[]{"Sample Inc.", "CH"}, new String[]{"Acme", "DE"},
                new String[]{"Beta | Corp", "AT"});
        table.setPrefHeight(120);

        TreeItem<String> root = new TreeItem<>("root");
        TreeItem<String> order = new TreeItem<>("Order 1001");
        order.getChildren().add(new TreeItem<>("Item A"));
        root.getChildren().add(order);
        root.getChildren().add(new TreeItem<>("Order 1002"));
        root.setExpanded(true);
        TreeView<String> tree = new TreeView<>(root);
        tree.setShowRoot(false);
        tree.setId("orders");
        tree.setPrefHeight(80);

        Slider volume = new Slider(0, 100, 10);
        volume.setId("volume");

        DatePicker date = new DatePicker();
        date.setId("date");
        int[] dateActions = {0};
        date.setOnAction(e -> status.setText("date actions " + (++dateActions[0])));

        Label generalContent = new Label("general content");
        generalContent.setId("generalContent");
        Tab general = new Tab("General", generalContent);
        Tab advanced = new Tab("Advanced", new Label("advanced content"));
        Tab closable = new Tab("Closable", new Label("closable content"));
        Tab fixed = new Tab("Fixed", new Label("fixed content"));
        fixed.setClosable(false);
        Tab locked = new Tab("Locked", new Label("locked content"));
        locked.setId("lockedTab");
        locked.setOnCloseRequest(e -> {
            e.consume();
            status.setText("close vetoed");
        });
        TabPane tabs = new TabPane(general, advanced, closable, fixed, locked);
        tabs.setId("tabs");
        tabs.setPrefHeight(60);

        MenuItem confirm = new MenuItem("Confirm...");
        confirm.setOnAction(e -> showConfirm(status));
        MenuItem save = new MenuItem("Save");
        save.setOnAction(e -> status.setText("Menu saved"));
        Menu export = new Menu("Export");
        MenuItem pdf = new MenuItem("PDF");
        pdf.setOnAction(e -> status.setText("Exported PDF"));
        export.getItems().add(pdf);
        MenuItem off = new MenuItem("Off");
        off.setDisable(true);
        off.setOnAction(e -> status.setText("disabled item fired"));
        Menu file = new Menu("File", null, save, export, confirm, off);
        MenuItem wipe = new MenuItem("Wipe");
        wipe.setId("wipeItem");
        wipe.setOnAction(e -> status.setText("Wiped"));
        wipe.setAccelerator(KeyCombination.keyCombination("Ctrl+W"));
        Menu danger = new Menu("Danger", null, wipe);
        MenuBar menuBar = new MenuBar(file, danger);
        menuBar.setId("menuBar");

        TreeItem<String[]> plant = new TreeItem<>(new String[]{"Plant 2", "running"});
        TreeItem<String[]> line = new TreeItem<>(new String[]{"Line | A", "stopped"});
        plant.getChildren().add(line);
        TreeItem<String[]> ttRoot = new TreeItem<>(new String[]{"root", ""});
        ttRoot.getChildren().add(plant);
        TreeTableView<String[]> machines = new TreeTableView<>(ttRoot);
        machines.setId("machines");
        machines.setShowRoot(false);
        TreeTableColumn<String[], String> ttName = new TreeTableColumn<>("Machine");
        ttName.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getValue()[0]));
        TreeTableColumn<String[], String> ttState = new TreeTableColumn<>("State");
        ttState.setCellValueFactory(c -> new SimpleStringProperty(c.getValue().getValue()[1]));
        machines.getColumns().add(ttName);
        machines.getColumns().add(ttState);
        machines.setPrefHeight(80);

        XYChart.Series<String, Number> series = new XYChart.Series<>();
        series.setName("Output");
        series.getData().add(new XYChart.Data<>("Mon", 3));
        series.getData().add(new XYChart.Data<>("Tue", 5));
        BarChart<String, Number> chart = new BarChart<>(new CategoryAxis(), new NumberAxis());
        chart.setId("chart");
        chart.setTitle("Weekly output");
        chart.getData().add(series);
        chart.setPrefSize(200, 120);
        chart.setAnimated(false);

        Canvas canvas = new Canvas(40, 20);
        canvas.setId("canvas");
        canvas.getGraphicsContext2D().setFill(Color.RED);
        canvas.getGraphicsContext2D().fillRect(0, 0, 20, 20);
        canvas.setOnMouseClicked(e -> status.setText("canvas " + (int) e.getX() + "," + (int) e.getY()));

        WritableImage pixels = new WritableImage(4, 3);
        ImageView image = new ImageView(pixels);
        image.setId("image");

        Accordion accordion = new Accordion(new TitledPane("First", new Label("1")), new TitledPane("Second", new Label("2")));
        accordion.setId("accordion");
        Pagination pages = new Pagination(5);
        pages.setId("pages");
        pages.setPrefHeight(60);
        Spinner<Integer> spinner = new Spinner<>(0, 10, 3);
        spinner.setId("spinner");

        Button twinA = new Button("Twin A");
        twinA.setId("twin");
        twinA.setOnAction(e -> status.setText("twin A"));
        Button twinB = new Button("Twin B");
        twinB.setId("twin");
        twinB.setOnAction(e -> status.setText("twin B"));
        CheckBox tri = new CheckBox("Tri");
        tri.setId("tri");
        tri.setAllowIndeterminate(true);

        HBox buttons = new HBox(ok, delete, purge, dupA, dupB, hidden, disabled, dialog, twinA, twinB);
        buttons.setId("buttons");
        List<javafx.scene.Node> rows = List.of(menuBar, status, new HBox(nameLabel, name), password, buttons,
                agree, tri, country, list, table, tree, volume, date, tabs, machines,
                new HBox(chart, canvas, image, spinner), accordion, pages, extras(status));
        VBox box = new VBox();
        box.getChildren().addAll(rows);
        return box;
    }
}
