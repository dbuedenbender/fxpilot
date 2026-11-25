package io.github.dbuedenbender.fxpilot.demo;

import javafx.beans.property.SimpleStringProperty;
import javafx.beans.property.StringProperty;
import javafx.collections.ObservableList;
import javafx.geometry.Insets;
import javafx.geometry.Pos;
import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.control.Button;
import javafx.scene.control.ComboBox;
import javafx.scene.control.Label;
import javafx.scene.control.TextField;
import javafx.scene.control.TreeItem;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTableView;
import javafx.scene.layout.GridPane;
import javafx.scene.layout.HBox;
import javafx.scene.layout.Priority;
import javafx.scene.layout.VBox;
import javafx.stage.Modality;
import javafx.stage.Stage;
import javafx.stage.Window;

import java.util.Locale;

/**
 * Orders tab: a TreeTableView of orders and their positions, plus a modal "New order" dialog with its
 * own controls (a second window for fx_list_windows and window=title:...).
 */
final class OrderView {

    /** One row of the tree table: an order or one of its positions. */
    static final class OrderRow {
        final StringProperty label = new SimpleStringProperty();
        final StringProperty customer = new SimpleStringProperty();
        final StringProperty status = new SimpleStringProperty();
        final StringProperty amount = new SimpleStringProperty();

        OrderRow(String label, String customer, String status, double amount) {
            this.label.set(label);
            this.customer.set(customer);
            this.status.set(status);
            this.amount.set(String.format(Locale.ROOT, "%.2f", amount));
        }

        @Override
        public String toString() {
            return label.get();
        }
    }

    private final ActivityLog log;
    private final ObservableList<Customer> customers;
    private final TreeItem<OrderRow> root = new TreeItem<>(new OrderRow("Orders", "", "", 0));
    private final TreeTableView<OrderRow> tree = new TreeTableView<>(root);
    private int nextOrder = 1004;

    OrderView(ActivityLog log, ObservableList<Customer> customers) {
        this.log = log;
        this.customers = customers;
        resetData();
    }

    void resetData() {
        root.getChildren().setAll(
                order("Order 1001", "Sample Inc.", "open",
                        position("Mixer M-200", 2, 450), position("Spare blade set", 4, 87.5)),
                order("Order 1002", "Contoso", "shipped", position("Scale S-10", 1, 1290)),
                order("Order 1003", "Northwind", "open",
                        position("Conveyor belt 5 m", 1, 2300), position("Installation", 1, 640)));
        nextOrder = 1004;
        root.setExpanded(true);
    }

    @SafeVarargs
    private static TreeItem<OrderRow> order(String label, String customer, String status, TreeItem<OrderRow>... positions) {
        double total = 0;
        for (TreeItem<OrderRow> p : positions) {
            total += Double.parseDouble(p.getValue().amount.get());
        }
        TreeItem<OrderRow> item = new TreeItem<>(new OrderRow(label, customer, status, total));
        item.getChildren().addAll(positions);
        return item;
    }

    private static TreeItem<OrderRow> position(String article, int quantity, double price) {
        return new TreeItem<>(new OrderRow(quantity + " x " + article, "", "", quantity * price));
    }

    Node build() {
        tree.setId("orderTree");
        tree.setShowRoot(false);
        tree.getColumns().add(column("Order", 180, r -> r.label));
        tree.getColumns().add(column("Customer", 130, r -> r.customer));
        tree.getColumns().add(column("Status", 90, r -> r.status));
        tree.getColumns().add(column("Amount", 90, r -> r.amount));

        Button expand = new Button("Expand all");
        expand.setId("expandAllButton");
        expand.setOnAction(e -> setExpanded(root, true));
        Button collapse = new Button("Collapse all");
        collapse.setId("collapseAllButton");
        collapse.setOnAction(e -> {
            setExpanded(root, false);
            root.setExpanded(true);
        });
        Button ship = new Button("Ship selected");
        ship.setId("shipButton");
        ship.setOnAction(e -> shipSelected());
        Button newOrder = new Button("New order...");
        newOrder.setId("newOrderButton");
        newOrder.setOnAction(e -> showNewOrderDialog(tree.getScene().getWindow()));

        HBox buttons = new HBox(8, expand, collapse, ship, newOrder);
        buttons.setAlignment(Pos.CENTER_LEFT);
        VBox box = new VBox(8, buttons, tree);
        VBox.setVgrow(tree, Priority.ALWAYS);
        box.setPadding(new Insets(12));
        return box;
    }

    private interface Getter {
        StringProperty get(OrderRow row);
    }

    private static TreeTableColumn<OrderRow, String> column(String title, double width, Getter getter) {
        TreeTableColumn<OrderRow, String> c = new TreeTableColumn<>(title);
        c.setPrefWidth(width);
        c.setCellValueFactory(f -> getter.get(f.getValue().getValue()));
        return c;
    }

    private static void setExpanded(TreeItem<?> item, boolean expanded) {
        item.setExpanded(expanded);
        for (TreeItem<?> child : item.getChildren()) {
            setExpanded(child, expanded);
        }
    }

    private void shipSelected() {
        TreeItem<OrderRow> item = tree.getSelectionModel().getSelectedItem();
        if (item != null && item.getParent() != root) {
            item = item.getParent();
        }
        if (item == null || item == root) {
            log.error("no order selected");
            return;
        }
        if ("shipped".equals(item.getValue().status.get())) {
            log.error(item.getValue().label.get() + " is already shipped");
            return;
        }
        item.getValue().status.set("shipped");
        tree.refresh();
        log.info("Shipped: " + item.getValue().label.get());
    }

    /** Modal dialog; the click that opens it returns status=pending until it is closed. */
    private void showNewOrderDialog(Window owner) {
        Stage dialog = new Stage();
        dialog.initOwner(owner);
        dialog.initModality(Modality.WINDOW_MODAL);
        dialog.setTitle("New order");

        ComboBox<Customer> customer = new ComboBox<>(customers);
        customer.setId("orderCustomer");
        customer.setPromptText("Customer");
        TextField article = new TextField();
        article.setId("orderArticle");
        article.setPromptText("Article");
        TextField amount = new TextField();
        amount.setId("orderAmount");
        amount.setPromptText("Amount");
        Label error = new Label();
        error.setId("orderError");
        error.setStyle("-fx-text-fill: #c62828;");

        Button create = new Button("Create");
        create.setId("createOrderButton");
        create.setDefaultButton(true);
        create.setOnAction(e -> {
            double value;
            try {
                value = Double.parseDouble(amount.getText().trim().replace(',', '.'));
            } catch (NumberFormatException ex) {
                error.setText("Amount must be a number");
                return;
            }
            if (customer.getValue() == null || article.getText().isBlank()) {
                error.setText("Customer and article are required");
                return;
            }
            String label = "Order " + nextOrder++;
            root.getChildren().add(order(label, customer.getValue().getName(), "open",
                    new TreeItem<>(new OrderRow("1 x " + article.getText().trim(), "", "", value))));
            log.info("Created: " + label + " for " + customer.getValue().getName());
            dialog.close();
        });
        Button cancel = new Button("Cancel");
        cancel.setId("cancelOrderButton");
        cancel.setCancelButton(true);
        cancel.setOnAction(e -> {
            log.info("New order cancelled");
            dialog.close();
        });

        GridPane form = new GridPane();
        form.setHgap(8);
        form.setVgap(6);
        form.addRow(0, new Label("Customer"), customer);
        form.addRow(1, new Label("Article"), article);
        form.addRow(2, new Label("Amount"), amount);
        HBox buttons = new HBox(8, create, cancel);
        VBox content = new VBox(10, form, error, buttons);
        content.setId("newOrderDialog");
        content.setPadding(new Insets(12));
        dialog.setScene(new Scene(content, 320, 200));
        dialog.showAndWait();
    }
}
