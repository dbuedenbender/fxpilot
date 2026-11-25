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
import javafx.scene.control.MultipleSelectionModel;
import javafx.scene.control.TableColumn;
import javafx.scene.control.TableColumnBase;
import javafx.scene.control.TableView;
import javafx.scene.control.TablePosition;
import javafx.scene.control.TreeTableColumn;
import javafx.scene.control.TreeTablePosition;
import javafx.scene.control.TreeTableView;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * TableView and TreeTableView operations.
 */
@SuppressWarnings({"unchecked", "rawtypes"})
final class TableOps {

    private TableOps() {
    }

    static Map<String, Object> tableCell(Map<String, String> p) {
        return FxThread.query(() -> {
            Node node = Params.node(p);
            int row = Params.intVal(p, "row", 0);
            String column = p.get("column");
            if (node instanceof TableView) {
                TableView<?> table = (TableView<?>) node;
                TableColumn<?, ?> col = tableColumn(table, column);
                if (row < 0 || row >= table.getItems().size()) {
                    throw new FxPilotException(404, Codes.ROW_NOT_FOUND, "Row " + row + " not found ("
                            + table.getItems().size() + " rows)");
                }
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("row", row);
                m.put("column", col.getText());
                m.put("value", col.getCellData(row));
                return m;
            }
            if (node instanceof TreeTableView) {
                TreeTableView<?> table = (TreeTableView<?>) node;
                TreeTableColumn<?, ?> col = treeTableColumn(table, column);
                if (row < 0 || row >= table.getExpandedItemCount()) {
                    throw new FxPilotException(404, Codes.ROW_NOT_FOUND, "Row " + row + " not found ("
                            + table.getExpandedItemCount() + " visible rows)");
                }
                // read the cell itself; splitting the row text breaks on cells that contain " | "
                Object value = NodeInfo.treeTableCellValue(col, table, table.getTreeItem(row));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("row", row);
                m.put("column", col.getText());
                m.put("value", value == null ? null : String.valueOf(value));
                return m;
            }
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TableView/TreeTableView");
        });
    }

    static void tableEdit(Node node, Map<String, String> p) {
        int row = Params.intVal(p, "row", 0);
        String column = p.get("column");
        if (node instanceof TableView) {
            TableView<?> table = (TableView<?>) node;
            TableColumn<?, ?> col = tableColumn(table, column);
            table.getSelectionModel().select(row);
            table.scrollTo(row);
            table.edit(row, (TableColumn) col);
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            TreeTableColumn<?, ?> col = treeTableColumn(table, column);
            table.getSelectionModel().select(row);
            table.edit(row, (TreeTableColumn) col);
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TableView/TreeTableView");
    }

    /**
     * Commits a new cell value the way an edited cell does: an edit-commit event on the column, which runs the
     * column's onEditCommit handler (by default it writes the value to the cell's property). This does not
     * depend on the cell being rendered, unlike typing into the editor.
     */
    static void tableCellSet(Node node, Map<String, String> p) {
        int row = Params.intVal(p, "row", 0);
        String column = p.get("column");
        String value = p.get("value") == null ? "" : p.get("value");
        try {
            if (node instanceof TableView) {
                TableView table = (TableView) node;
                TableColumn col = tableColumn(table, column);
                requireEditable(table.isEditable() && col.isEditable(), col.getText());
                if (row < 0 || row >= table.getItems().size()) {
                    throw new FxPilotException(404, Codes.ROW_NOT_FOUND, "Row " + row + " not found");
                }
                Event.fireEvent(col, new TableColumn.CellEditEvent(table, new TablePosition(table, row, col),
                        TableColumn.editCommitEvent(), value));
                table.refresh();
                return;
            }
            if (node instanceof TreeTableView) {
                TreeTableView table = (TreeTableView) node;
                TreeTableColumn col = treeTableColumn(table, column);
                requireEditable(table.isEditable() && col.isEditable(), col.getText());
                if (row < 0 || row >= table.getExpandedItemCount()) {
                    throw new FxPilotException(404, Codes.ROW_NOT_FOUND, "Row " + row + " not found");
                }
                Event.fireEvent(col, new TreeTableColumn.CellEditEvent(table, new TreeTablePosition(table, row, col),
                        TreeTableColumn.editCommitEvent(), value));
                table.refresh();
                return;
            }
        } catch (ClassCastException e) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "Column '" + column + "' does not hold text values: " + e.getMessage());
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TableView/TreeTableView");
    }

    private static void requireEditable(boolean editable, String column) {
        if (!editable) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_ACTION, "Column '" + column + "' is not editable");
        }
    }

    static void tableSort(Node node, Map<String, String> p) {
        String column = Params.req(p, "column");
        String order = Params.opt(p, "order", "ASCENDING");
        if (node instanceof TableView) {
            TableView<?> table = (TableView<?>) node;
            TableColumn<?, ?> col = tableColumn(table, column);
            col.setSortType("DESCENDING".equalsIgnoreCase(order)
                    ? TableColumn.SortType.DESCENDING : TableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll((TableColumn) col);
            table.sort();
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            TreeTableColumn<?, ?> col = treeTableColumn(table, column);
            col.setSortType("DESCENDING".equalsIgnoreCase(order)
                    ? TreeTableColumn.SortType.DESCENDING : TreeTableColumn.SortType.ASCENDING);
            table.getSortOrder().setAll((TreeTableColumn) col);
            table.sort();
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TableView/TreeTableView");
    }

    static void tableColumn(Node node, Map<String, String> p) {
        String column = Params.req(p, "column");
        String action = Params.opt(p, "action", "visible");
        if (node instanceof TableView) {
            TableView<?> table = (TableView<?>) node;
            TableColumn<?, ?> col = tableColumn(table, column);
            switch (action) {
                case "visible": col.setVisible(Boolean.parseBoolean(Params.opt(p, "value", "true"))); break;
                case "width": col.setPrefWidth(Params.doubleVal(p, "value", col.getWidth())); break;
                case "order": {
                    int index = Params.intVal(p, "value", table.getColumns().indexOf(col));
                    table.getColumns().remove(col);
                    table.getColumns().add(Math.max(0, Math.min(index, table.getColumns().size())), (TableColumn) col);
                    break;
                }
                case "text": col.setText(Params.opt(p, "value", col.getText())); break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown column action '" + action + "'");
            }
            return;
        }
        if (node instanceof TreeTableView) {
            TreeTableView<?> table = (TreeTableView<?>) node;
            TreeTableColumn<?, ?> col = treeTableColumn(table, column);
            switch (action) {
                case "visible": col.setVisible(Boolean.parseBoolean(Params.opt(p, "value", "true"))); break;
                case "width": col.setPrefWidth(Params.doubleVal(p, "value", col.getWidth())); break;
                case "order": {
                    int index = Params.intVal(p, "value", table.getColumns().indexOf(col));
                    table.getColumns().remove(col);
                    table.getColumns().add(Math.max(0, Math.min(index, table.getColumns().size())), (TreeTableColumn) col);
                    break;
                }
                case "text": col.setText(Params.opt(p, "value", col.getText())); break;
                default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown column action '" + action + "'");
            }
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TableView/TreeTableView");
    }

    static void tableSelect(Node node, Map<String, String> p) {
        int row = Params.intVal(p, "row", 0);
        MultipleSelectionModel<?> model = SelectionOps.selectionModel(node);
        if (model == null) {
            throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " has no selection model");
        }
        model.clearAndSelect(row);
        if (node instanceof TableView) {
            ((TableView<?>) node).scrollTo(row);
        } else if (node instanceof TreeTableView) {
            ((TreeTableView<?>) node).scrollTo(row);
        }
    }

    static void tableScroll(Node node, Map<String, String> p) {
        int row = Params.intVal(p, "row", 0);
        if (node instanceof TableView) {
            ((TableView<?>) node).scrollTo(row);
            return;
        }
        if (node instanceof TreeTableView) {
            ((TreeTableView<?>) node).scrollTo(row);
            return;
        }
        throw new FxPilotException(400, Codes.UNSUPPORTED_NODE, Selectors.typeName(node) + " is not a TableView/TreeTableView");
    }

    static TableColumn<?, ?> tableColumn(TableView<?> table, String spec) {
        int index = columnIndex(table.getColumns(), spec);
        if (index < 0 || index >= table.getColumns().size()) {
            throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Column '" + spec + "' not found");
        }
        return table.getColumns().get(index);
    }

    private static TreeTableColumn<?, ?> treeTableColumn(TreeTableView<?> table, String spec) {
        int index = columnIndex(table.getColumns(), spec);
        if (index < 0 || index >= table.getColumns().size()) {
            throw new FxPilotException(404, Codes.ITEM_NOT_FOUND, "Column '" + spec + "' not found");
        }
        return table.getColumns().get(index);
    }

    private static int columnIndex(List<? extends TableColumnBase<?, ?>> columns, String spec) {
        if (spec == null || spec.isBlank()) {
            return 0;
        }
        try {
            return Integer.parseInt(spec.trim());
        } catch (NumberFormatException ignored) {
            // fall through to text matching
        }
        for (int i = 0; i < columns.size(); i++) {
            if (spec.equals(columns.get(i).getText())) {
                return i;
            }
        }
        for (int i = 0; i < columns.size(); i++) {
            if (columns.get(i).getText() != null && columns.get(i).getText().contains(spec)) {
                return i;
            }
        }
        return -1;
    }
}
