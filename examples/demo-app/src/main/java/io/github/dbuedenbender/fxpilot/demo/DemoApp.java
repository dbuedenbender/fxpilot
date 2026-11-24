package io.github.dbuedenbender.fxpilot.demo;

import io.github.dbuedenbender.fxpilot.FxPilotServer;
import javafx.application.Application;
import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.geometry.Insets;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.CheckBox;
import javafx.scene.control.Label;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextField;
import javafx.scene.control.cell.PropertyValueFactory;
import javafx.scene.layout.VBox;
import javafx.stage.Stage;

/**
 * Small JavaFX demo used by the FxPilot examples and replay plan.
 */
public final class DemoApp extends Application {

    private final Label status = new Label("Ready");
    private final TextField name = new TextField();
    private final CheckBox active = new CheckBox("Active");
    private final TableView<Customer> customers = new TableView<>();

    @Override
    public void start(Stage stage) {
        name.setId("nameField");
        name.setPromptText("Customer name");
        active.setId("activeCheck");
        status.setId("statusLabel");

        Button save = new Button("Save");
        save.setId("saveButton");
        save.setOnAction(event -> {
            String customer = name.getText() == null || name.getText().isBlank()
                    ? "(empty)" : name.getText();
            status.setText("Saved: " + customer + (active.isSelected() ? " (active)" : ""));
        });

        TableColumn<Customer, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setCellValueFactory(new PropertyValueFactory<>("name"));
        TableColumn<Customer, String> countryColumn = new TableColumn<>("Country");
        countryColumn.setCellValueFactory(new PropertyValueFactory<>("country"));
        customers.setId("customerTable");
        customers.getColumns().add(nameColumn);
        customers.getColumns().add(countryColumn);
        customers.getItems().addAll(new Customer("Sample Inc.", "CH"), new Customer("Contoso", "DE"));

        VBox root = new VBox(10,
                new Label("FxPilot demo"),
                name,
                active,
                save,
                status,
                customers);
        root.setId("root");
        root.setPadding(new Insets(16));

        stage.setTitle("FxPilot Demo");
        stage.setScene(new Scene(root, 640, 420));
        stage.show();

        FxPilotServer.setNotes("Demo app: Save only updates the status label; do not use for production data.");
        FxPilotServer.startIfEnabled();
    }

    @Override
    public void stop() {
        FxPilotServer.stop();
    }

    public static void main(String[] args) {
        launch(args);
    }

    public static final class Customer {
        private final StringProperty name = new SimpleStringProperty();
        private final StringProperty country = new SimpleStringProperty();

        Customer(String name, String country) {
            this.name.set(name);
            this.country.set(country);
        }

        public StringProperty nameProperty() {
            return name;
        }

        public StringProperty countryProperty() {
            return country;
        }

        public String getName() {
            return name.get();
        }

        public String getCountry() {
            return country.get();
        }
    }
}
