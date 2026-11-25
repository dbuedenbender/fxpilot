package io.github.dbuedenbender.fxpilot.modulardemo;

import io.github.dbuedenbender.fxpilot.FxPilotServer;
import io.github.dbuedenbender.fxpilot.modulardemo.secret.VaultLabel;
import io.github.dbuedenbender.fxpilot.modulardemo.widgets.SerialButton;
import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.collections.FXCollections;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.layout.HBox;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Small JPMS application: it requires FxPilot as a module and starts the endpoint itself. It has a form,
 * a table and two custom controls, one in an exported and one in a non-exported package.
 */
public final class ModularDemoApp extends Application {

    @Override
    public void start(Stage stage) {
        Label status = new Label("Ready");
        status.setId("statusLabel");

        TextField machine = new TextField();
        machine.setId("machineField");
        machine.setPromptText("Machine name");
        ComboBox<String> line = new ComboBox<>(FXCollections.observableArrayList("Line 1", "Line 2", "Line 3"));
        line.setId("lineCombo");

        TableView<String[]> machines = new TableView<>();
        machines.setId("machineTable");
        TableColumn<String[], String> name = new TableColumn<>("Machine");
        name.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()[0]));
        TableColumn<String[], String> where = new TableColumn<>("Line");
        where.setCellValueFactory(c -> new SimpleStringProperty(c.getValue()[1]));
        machines.getColumns().add(name);
        machines.getColumns().add(where);
        machines.getItems().add(new String[]{"Mixer M-200", "Line 1"});
        machines.setPrefHeight(160);

        Button add = new Button("Add");
        add.setId("addButton");
        add.setDefaultButton(true);
        add.setOnAction(e -> {
            String text = machine.getText() == null ? "" : machine.getText().trim();
            if (text.isEmpty() || line.getValue() == null) {
                status.setText("Error: machine and line are required");
                return;
            }
            machines.getItems().add(new String[]{text, line.getValue()});
            status.setText("Added: " + text + " on " + line.getValue());
        });

        SerialButton serial = new SerialButton("Identify", "SN-4711");
        serial.setId("serialButton");
        serial.setOnAction(e -> status.setText("Serial: " + serial.getSerial()));
        VaultLabel vault = new VaultLabel("Vault", "do-not-read");
        vault.setId("vaultLabel");

        VBox root = new VBox(10,
                new Label("Modular FxPilot demo (" + getClass().getModule().getName() + ")"),
                new HBox(8, machine, line, add),
                machines,
                new HBox(8, serial, vault),
                status);
        root.setId("root");
        root.setPadding(new Insets(12));

        stage.setTitle("FxPilot Modular Demo");
        stage.setScene(new Scene(root, 520, 360));
        stage.show();

        FxPilotServer.setNotes("Modular demo; all data is in memory. The app runs on the module path.");
        FxPilotServer.startIfEnabled();
    }

    @Override
    public void stop() {
        FxPilotServer.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }
}
