# FxPilot Demo: Order Desk

A small JavaFX order-desk application with stable `fx:id`s for trying FxPilot. It has customers, orders, a dashboard and settings, so nearly every FxPilot tool has something to work on, and a few destructive actions to try `fxpilot.deny`, `fxpilot.dangerous` and `fxpilot.readonly` on. All data is kept in memory.

Requirements: JDK 17 or newer (the demo uses JavaFX 21).

## Build

```bat
mvnw.cmd -DskipTests install
mvnw.cmd -f examples\demo-app\pom.xml package
```

The first command installs the current FxPilot into the local Maven repository; once the version is on Maven Central, the second one is enough.

## Run

| Command | Endpoint settings |
|---|---|
| `mvnw.cmd -f examples\demo-app\pom.xml javafx:run` | port 8765, no token |
| `mvnw.cmd -f examples\demo-app\pom.xml javafx:run@safe` | port 8765, `token=auto`, `deny=#deleteAllButton,#resetButton,.danger`, `dangerous=false` |
| `mvnw.cmd -f examples\demo-app\pom.xml javafx:run@readonly` | port 8765, `readonly=true` |

Without Maven, start `io.github.dbuedenbender.fxpilot.demo.Launcher` with JavaFX and `fxpilot.jar` on the classpath and `-Dfxpilot.port=8765`. `Launcher` exists because JavaFX refuses a main class that extends `Application` when JavaFX is not on the module path.

## IntelliJ IDEA

The repository contains a shared run configuration:

```text
.run/FxPilot Demo (Maven).run.xml
```

Open the project in IntelliJ and select **FxPilot Demo (Maven)** in the run-configuration dropdown, then click Run. **FxPilot Demo safe (Maven)** starts the `safe` variant. If the configurations are not visible, import `examples/demo-app/pom.xml` as a Maven project once.

## What is in the app

The menu bar has File (New customer `Ctrl+N`, Save `Ctrl+S`, Export > CSV/PDF, Delete all customers `Ctrl+Shift+D`, Exit), Edit (Clear form, Sync now `F5`) and Help (About, a modal dialog). Every action writes its result to `#statusLabel` and to the activity list on the dashboard.

### Customers tab (`#customersTab`)

| Selector | Control |
|---|---|
| `#nameField`, `label=Name` | Name (required) |
| `#emailField` | Email (must contain `@`) |
| `#countryCombo` | Editable ComboBox: DE, CH, AT, FR, IT |
| `#typeChoice` | ChoiceBox: Retail, Wholesale, Partner |
| `#activeCheck` | CheckBox |
| `#priorityLow`, `#priorityNormal`, `#priorityHigh` | RadioButtons |
| `#creditSlider` | Slider 0 to 50 000 |
| `#discountSpinner` | Spinner 0 to 30 |
| `#sinceDate` | DatePicker |
| `#pinField` | PasswordField; FxPilot shows it as `***` |
| `#notesArea` | TextArea |
| `#saveButton` | Default button (ENTER): adds the customer or updates the one with the same name |
| `#clearButton` | Cancel button (ESCAPE) |
| `#deleteButton` | Deletes the selected row (style class `danger`) |
| `#deleteAllButton` | Asks in a modal dialog, then deletes all customers (style class `danger`) |
| `#syncButton`, `#syncProgress` | Background task of about 1.5 s; ends with "Synced N customers" |
| `#searchField` | Filters the table by name or country |
| `#customerTable` | Editable name column (double click), context menu Duplicate / Delete |

### Orders tab (`#ordersTab`)

| Selector | Control |
|---|---|
| `#orderTree` | TreeTableView: orders with their positions, columns Order, Customer, Status, Amount |
| `#expandAllButton`, `#collapseAllButton` | Expand or collapse all orders |
| `#shipButton` | Sets the selected order to `shipped` |
| `#newOrderButton` | Opens the modal window "New order" with `#orderCustomer`, `#orderArticle`, `#orderAmount`, `#createOrderButton`, `#cancelOrderButton` |

### Dashboard tab (`#dashboardTab`)

`#revenueChart` (bar chart), `#countryChart` (pie chart, follows the customer list), `#signatureCanvas` (draw with the mouse, `#clearSignatureButton`) and `#activityList`.

### Settings tab (`#settingsTab`)

`#settingsAccordion` with General (`#languageChoice`, `#autosaveToggle`), Display (`#accentPicker`, `#fontSizeSlider`) and Advanced (`#docsLink`, `#resetButton`), plus `#tipsPagination`.

## Things to try with an MCP client

- "Call fx_health and tell me what this app warns about." The notes list the destructive actions.
- "Add the customer Acme Tools from Austria, wholesale, active, credit limit 20000, and check the status line."
- "Create an order for Contoso over 990 EUR." Clicking New order returns `status: pending` because the dialog is modal; the dialog then appears in `fx_list_windows`.
- "Sync and wait until it is done." This needs `fx_wait_for` on `#statusLabel`.
- "Ship order 1003 and read the revenue chart."
- Start with `javafx:run@safe` and ask the agent to delete all customers: the button, the menu item and its shortcut are all rejected with `NODE_DENIED`, and closing the window is rejected with `DANGEROUS_DISABLED`.

## Replay plans

With the demo running in the default mode:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json
java -jar fxpilot.jar --replay examples\plans\demo-orders.json
```

Both plans start with `#resetButton`, so they run in any order and repeatedly. In the `safe` mode that button is denied, so the plans fail on their first step there.
