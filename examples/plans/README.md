# Replay plans

## Demo smoke test

Start the demo app first:

```bat
mvn -f examples\demo-app\pom.xml javafx:run
```

Then replay the plan:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json
```

Useful CI options:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json ^
  --retry 2 ^
  --screenshot-on-failure ^
  --report fxpilot\demo-report.xml
```

## Variables

Create a copy of the plan and use placeholders:

```json
"text": "${customer}"
```

Then run:

```bat
java -jar fxpilot.jar --replay examples\plans\demo-smoke.json --var customer="Contoso"
```
