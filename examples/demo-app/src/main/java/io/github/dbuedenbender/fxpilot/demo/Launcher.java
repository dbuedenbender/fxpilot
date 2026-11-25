package io.github.dbuedenbender.fxpilot.demo;

import javafx.application.Application;

/**
 * Entry point for classpath launches (IDE, plain {@code java -cp}). JavaFX refuses to start a main class
 * that extends {@link Application} when JavaFX is not on the module path.
 */
public final class Launcher {

    private Launcher() {
    }

    public static void main(String[] args) {
        Startup.silenceClasspathNotice();
        Application.launch(DemoApp.class, args);
    }
}
