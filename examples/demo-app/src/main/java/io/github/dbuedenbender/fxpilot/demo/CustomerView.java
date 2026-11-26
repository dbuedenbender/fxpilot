package io.github.dbuedenbender.fxpilot.demo;

import javafx.beans.Observable;
import javafx.collections.FXCollections;
import javafx.collections.ObservableList;
import javafx.collections.transformation.FilteredList;
import javafx.concurrent.Task;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.control.Alert;
import javafx.scene.control.Button;
import javafx.scene.control.ButtonType;
import javafx.scene.control.CheckBox;
import javafx.scene.control.ChoiceBox;
import javafx.scene.control.ComboBox;
import javafx.scene.control.ContextMenu;
import javafx.scene.control.DatePicker;
import javafx.scene.control.Label;
import javafx.scene.control.MenuItem;
import javafx.scene.control.PasswordField;
import javafx.scene.control.ProgressBar;
import javafx.scene.control.RadioButton;
import javafx.scene.control.Slider;
import javafx.scene.control.Spinner;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableView;
import javafx.scene.control.TextArea;
import javafx.scene.control.TextField;
import javafx.scene.control.ToggleGroup;
import javafx.scene.control.Tooltip;
import javafx.scene.control.cell.CheckBoxTableCell;
import javafx.scene.control.cell.TextFieldTableCell;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;

import java.time.LocalDate;
import java.util.Locale;
import java.util.Optional;

/**
 * Customers tab: a form with most input controls FxPilot supports, an editable, filterable table and
 * the buttons the deny list is meant for (delete, delete all).
 */
final class CustomerView {

    private final ActivityLog log;
    // the extractor lets the country chart follow edits, not only added or removed customers
    private final ObservableList<Customer> customers = FXCollections.observableArrayList(
            c -> new Observable[] {c.countryProperty()});
    private final FilteredList<Customer> filtered = new FilteredList<>(customers, c -> true);
    private final TableView<Customer> table = new TableView<>(filtered);

    private final TextField name = new TextField();
    private final TextField email = new TextField();
    private final ComboBox<String> country = new ComboBox<>(FXCollections.observableArrayList("DE", "CH", "AT", "FR", "IT"));
    private final ChoiceBox<String> type = new ChoiceBox<>(FXCollections.observableArrayList("Retail", "Wholesale", "Partner"));
    private final CheckBox active = new CheckBox("Active");
    private final ToggleGroup priority = new ToggleGroup();
    private final Slider creditLimit = new Slider(0, 50_000, 10_000);
    private final Spinner<Integer> discount = new Spinner<>(0, 30, 0);
    private final DatePicker since = new DatePicker();
    private final TextArea notes = new TextArea();
    private final PasswordField pin = new PasswordField();
    private final ProgressBar syncProgress = new ProgressBar(0);
    private final Button sync = new Button("Sync");

    CustomerView(ActivityLog log) {
        this.log = log;
        resetData();
    }

    ObservableList<Customer> customers() {
        return customers;
    }

    void resetData() {
        customers.setAll(
                new Customer("Sample Inc.", "office@sample.example", "CH", "Wholesale", true, 25_000),
                new Customer("Contoso", "info@contoso.example", "DE", "Retail", true, 5_000),
                new Customer("Fabrikam", "sales@fabrikam.example", "AT", "Partner", false, 12_000),
                new Customer("Northwind", "hello@northwind.example", "DE", "Retail", true, 8_000));
    }

    Node build() {
        GridPane form = new GridPane();
        form.setId("customerForm");
        form.setHgap(8);
        form.setVgap(6);
        int row = 0;
        form.addRow(row++, label("Name", name), name);
        form.addRow(row++, label("Email", email), email);
        form.addRow(row++, label("Country", country), country);
        form.addRow(row++, label("Type", type), type);
        form.addRow(row++, new Label(), active);
        form.addRow(row++, new Label("Priority"), priorityButtons());
        form.addRow(row++, label("Credit limit", creditLimit), creditLimit);
        form.addRow(row++, label("Discount %", discount), discount);
        form.addRow(row++, label("Customer since", since), since);
        form.addRow(row++, label("PIN", pin), pin);
        form.addRow(row, label("Notes", notes), notes);

        name.setId("nameField");
        name.setPromptText("Customer name");
        email.setId("emailField");
        email.setPromptText("name@example.com");
        country.setId("countryCombo");
        country.setEditable(true);
        type.setId("typeChoice");
        active.setId("activeCheck");
        creditLimit.setId("creditSlider");
        creditLimit.setShowTickLabels(true);
        creditLimit.setMajorTickUnit(10_000);
        creditLimit.setBlockIncrement(1_000);
        discount.setId("discountSpinner");
        since.setId("sinceDate");
        pin.setId("pinField");
        pin.setTooltip(new Tooltip("4 digits, never shown in FxPilot snapshots"));
        notes.setId("notesArea");
        notes.setPrefRowCount(3);

        Button save = new Button("Save");
        save.setId("saveButton");
        save.setDefaultButton(true);
        save.setTooltip(new Tooltip("Adds the customer, or updates the one with the same name"));
        save.setOnAction(e -> save());

        Button clear = new Button("Clear");
        clear.setId("clearButton");
        clear.setCancelButton(true);
        clear.setOnAction(e -> clearForm());

        Button delete = new Button("Delete selected");
        delete.setId("deleteButton");
        delete.getStyleClass().add("danger");
        delete.setOnAction(e -> deleteSelected());

        Button deleteAll = new Button("Delete all");
        deleteAll.setId("deleteAllButton");
        deleteAll.getStyleClass().add("danger");
        deleteAll.setStyle("-fx-text-fill: #c62828;");
        deleteAll.setOnAction(e -> deleteAll());

        sync.setId("syncButton");
        sync.setTooltip(new Tooltip("Simulates a slow backend call (about 1.5 s)"));
        sync.setOnAction(e -> sync());
        syncProgress.setId("syncProgress");
        syncProgress.setVisible(false);

        HBox buttons = new HBox(8, save, clear, delete, deleteAll, sync, syncProgress);
        buttons.setId("customerButtons");
        buttons.setAlignment(Pos.CENTER_LEFT);

        TextField search = new TextField();
        search.setId("searchField");
        search.setPromptText("Filter by name or country");
        search.textProperty().addListener((obs, old, text) -> {
            String needle = text == null ? "" : text.trim().toLowerCase(Locale.ROOT);
            filtered.setPredicate(c -> needle.isEmpty()
                    || c.getName().toLowerCase(Locale.ROOT).contains(needle)
                    || c.getCountry().toLowerCase(Locale.ROOT).contains(needle));
        });

        buildTable();

        VBox left = new VBox(10, form, buttons);
        VBox right = new VBox(6, search, table);
        VBox.setVgrow(table, Priority.ALWAYS);
        HBox.setHgrow(right, Priority.ALWAYS);
        HBox content = new HBox(16, left, right);
        content.setPadding(new Insets(12));
        return content;
    }

    private static Label label(String text, Node target) {
        Label label = new Label(text);
        label.setLabelFor(target);
        return label;
    }

    private Node priorityButtons() {
        HBox box = new HBox(8);
        box.setId("priorityGroup");
        for (String p : new String[]{"Low", "Normal", "High"}) {
            RadioButton b = new RadioButton(p);
            b.setId("priority" + p);
            b.setToggleGroup(priority);
            b.setUserData(p);
            b.setSelected("Normal".equals(p));
            box.getChildren().add(b);
        }
        return box;
    }

    private void buildTable() {
        table.setId("customerTable");
        table.setEditable(true);
        table.setPlaceholder(new Label("No customers"));

        TableColumn<Customer, String> nameColumn = new TableColumn<>("Name");
        nameColumn.setId("nameColumn");
        nameColumn.setCellValueFactory(c -> c.getValue().nameProperty());
        nameColumn.setCellFactory(TextFieldTableCell.forTableColumn());
        nameColumn.setOnEditCommit(e -> {
            e.getRowValue().nameProperty().set(e.getNewValue());
            log.info("Renamed: " + e.getOldValue() + " -> " + e.getNewValue());
        });
        nameColumn.setPrefWidth(140);

        TableColumn<Customer, String> countryColumn = new TableColumn<>("Country");
        countryColumn.setCellValueFactory(c -> c.getValue().countryProperty());
        TableColumn<Customer, String> typeColumn = new TableColumn<>("Type");
        typeColumn.setCellValueFactory(c -> c.getValue().typeProperty());
        TableColumn<Customer, Boolean> activeColumn = new TableColumn<>("Active");
        activeColumn.setCellValueFactory(c -> c.getValue().activeProperty());
        activeColumn.setCellFactory(CheckBoxTableCell.forTableColumn(activeColumn));
        TableColumn<Customer, Number> creditColumn = new TableColumn<>("Credit limit");
        creditColumn.setCellValueFactory(c -> c.getValue().creditLimitProperty());

        table.getColumns().add(nameColumn);
        table.getColumns().add(countryColumn);
        table.getColumns().add(typeColumn);
        table.getColumns().add(activeColumn);
        table.getColumns().add(creditColumn);

        MenuItem duplicate = new MenuItem("Duplicate");
        duplicate.setId("duplicateItem");
        duplicate.setOnAction(e -> duplicateSelected());
        MenuItem deleteRow = new MenuItem("Delete");
        deleteRow.setId("deleteRowItem");
        deleteRow.getStyleClass().add("danger");
        deleteRow.setOnAction(e -> deleteSelected());
        table.setContextMenu(new ContextMenu(duplicate, deleteRow));

        table.getSelectionModel().selectedItemProperty().addListener((obs, old, c) -> {
            if (c != null) {
                load(c);
            }
        });
    }

    private void load(Customer c) {
        name.setText(c.getName());
        email.setText(c.emailProperty().get());
        country.setValue(c.getCountry());
        type.setValue(c.typeProperty().get());
        active.setSelected(c.activeProperty().get());
        creditLimit.setValue(c.creditLimitProperty().get());
        discount.getValueFactory().setValue(c.discountProperty().get());
        since.setValue(c.sinceProperty().get());
        priority.getToggles().stream()
                .filter(t -> c.priorityProperty().get().equals(t.getUserData()))
                .findFirst().ifPresent(t -> t.setSelected(true));
        log.info("Loaded: " + c.getName());
    }

    void save() {
        String customerName = name.getText() == null ? "" : name.getText().trim();
        if (customerName.isEmpty()) {
            name.setStyle("-fx-border-color: #c62828;");
            log.error("name is required");
            return;
        }
        String mail = email.getText() == null ? "" : email.getText().trim();
        if (!mail.isEmpty() && !mail.contains("@")) {
            email.setStyle("-fx-border-color: #c62828;");
            log.error("email is invalid");
            return;
        }
        name.setStyle("");
        email.setStyle("");
        Customer target = customers.stream().filter(c -> c.getName().equals(customerName)).findFirst().orElse(null);
        boolean created = target == null;
        if (created) {
            target = new Customer(customerName, mail, "", "", false, 0);
            customers.add(target);
        }
        target.emailProperty().set(mail);
        target.countryProperty().set(country.getValue() == null ? "" : country.getValue());
        target.typeProperty().set(type.getValue() == null ? "" : type.getValue());
        target.activeProperty().set(active.isSelected());
        target.creditLimitProperty().set(Math.round(creditLimit.getValue()));
        target.discountProperty().set(discount.getValue());
        target.sinceProperty().set(since.getValue() == null ? LocalDate.now() : since.getValue());
        target.priorityProperty().set(priority.getSelectedToggle() == null
                ? "Normal" : String.valueOf(priority.getSelectedToggle().getUserData()));
        table.refresh();
        log.info("Saved: " + customerName + (active.isSelected() ? " (active)" : "")
                + (created ? "" : " [updated]"));
    }

    void clearForm() {
        table.getSelectionModel().clearSelection();
        name.clear();
        email.clear();
        country.setValue(null);
        type.setValue(null);
        active.setSelected(false);
        creditLimit.setValue(10_000);
        discount.getValueFactory().setValue(0);
        since.setValue(null);
        notes.clear();
        pin.clear();
        priority.getToggles().get(1).setSelected(true);
        name.setStyle("");
        email.setStyle("");
        log.info("Form cleared");
    }

    private void duplicateSelected() {
        Customer c = table.getSelectionModel().getSelectedItem();
        if (c == null) {
            log.error("no customer selected");
            return;
        }
        Customer copy = new Customer(c.getName() + " (copy)", c.emailProperty().get(), c.getCountry(),
                c.typeProperty().get(), c.activeProperty().get(), c.creditLimitProperty().get());
        customers.add(copy);
        log.info("Duplicated: " + c.getName());
    }

    private void deleteSelected() {
        Customer c = table.getSelectionModel().getSelectedItem();
        if (c == null) {
            log.error("no customer selected");
            return;
        }
        customers.remove(c);
        log.info("Deleted: " + c.getName());
    }

    /** Asks in a modal dialog first; FxPilot reports the click as status=pending while it is open. */
    void deleteAll() {
        Alert confirm = new Alert(Alert.AlertType.CONFIRMATION,
                "Delete all " + customers.size() + " customers?", ButtonType.OK, ButtonType.CANCEL);
        confirm.setTitle("Delete all customers");
        confirm.setHeaderText("This cannot be undone");
        Optional<ButtonType> answer = confirm.showAndWait();
        if (answer.isPresent() && answer.get() == ButtonType.OK) {
            int count = customers.size();
            customers.clear();
            log.info("Deleted all customers (" + count + ")");
        } else {
            log.info("Delete all cancelled");
        }
    }

    /** Background task, so callers have to wait for the result (fx_wait_for). */
    void sync() {
        Task<Integer> task = new Task<>() {
            @Override
            protected Integer call() throws Exception {
                for (int i = 1; i <= 15; i++) {
                    Thread.sleep(100);
                    updateProgress(i, 15);
                }
                return customers.size();
            }
        };
        syncProgress.progressProperty().bind(task.progressProperty());
        syncProgress.setVisible(true);
        sync.setDisable(true);
        log.info("Syncing...");
        task.setOnSucceeded(e -> {
            sync.setDisable(false);
            syncProgress.setVisible(false);
            log.info("Synced " + task.getValue() + " customers");
        });
        task.setOnFailed(e -> {
            sync.setDisable(false);
            syncProgress.setVisible(false);
            log.error("sync failed: " + task.getException());
        });
        Thread worker = new Thread(task, "demo-sync");
        worker.setDaemon(true);
        worker.start();
    }
}
