# Replay plans

## Demo plans

| Plan | Steps |
|---|---|
| [`demo-smoke.json`](demo-smoke.json) | Fills the customer form with every control type (text, ComboBox, ChoiceBox, CheckBox, RadioButton, Slider, Spinner, DatePicker), saves, checks the table and the filter, then checks the validation error |
| [`modular-smoke.json`](modular-smoke.json) | For `modular-demo`: validation error, adding a machine with ENTER, table selection, a custom control and a property read from an exported package |
| [`demo-orders.json`](demo-orders.json) | Expands and selects in the order TreeTableView, ships an order, creates one in the modal "New order" window and waits for the background sync |

Both start with `#resetButton`, so they run in any order and repeatedly. Start the demo app first (default mode, not `@safe`, where the reset button is denied):

```bat
mvn -f examples\demo-app\pom.xml javafx:run
```

Then replay the plans:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json
java -jar fxpilot.jar --replay examples\plans\demo-orders.json
```

Useful CI options:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json ^
  --retry 2 ^
  --screenshot-on-failure ^
  --report fxpilot\demo-report.xml
```

## Variables

Create a copy of the smoke plan and replace the customer name with a placeholder in the `fx_set_text` and `fx_assert` steps:

```json
"text": "${customer}"
```

Then run:

```bat
java -jar fxpilot.jar --replay my-plan.json --var customer="Contoso Ltd"
```
