package io.github.dbuedenbender.fxpilot.demo;

import java.util.logging.Logger;

/** Startup helpers shared by the demo's entry points. */
final class Startup {

    private Startup() {
    }

    /**
     * This demo runs JavaFX from the class path on purpose (plain {@code java -cp} and the replay
     * script). JavaFX logs an "Unsupported JavaFX configuration" notice once for that; it is
     * expected here, so drop just that message instead of the whole logger.
     */
    static void silenceClasspathNotice() {
        Logger.getLogger("javafx").setFilter(record ->
                !String.valueOf(record.getMessage()).startsWith("Unsupported JavaFX configuration"));
    }
}
