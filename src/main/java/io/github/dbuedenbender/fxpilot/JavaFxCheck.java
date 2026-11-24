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

/**
 * Checks the JavaFX runtime before the endpoint starts. Kept free of JavaFX references so that it can
 * be loaded even when JavaFX is missing ({@link FxPilotServer} cannot, its verification needs JavaFX).
 */
final class JavaFxCheck {

    private static final int MIN_JAVAFX_MAJOR = 11;

    private JavaFxCheck() {
    }

    /**
     * Checks that JavaFX is visible to the class loader of this class and is at least version
     * {@value #MIN_JAVAFX_MAJOR}. The version is only known once the toolkit has started; before that
     * (e.g. in the agent's premain) only the presence is checked.
     */
    static boolean supported() {
        try {
            Class.forName("javafx.application.Platform", false, JavaFxCheck.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError e) {
            System.err.println("[fxpilot] JavaFX not found on the class path or module path, FxPilot disabled");
            return false;
        }
        String version = System.getProperty("javafx.runtime.version", System.getProperty("javafx.version"));
        if (version == null) {
            return true;
        }
        return checkVersion(version);
    }

    /**
     * Checks a JavaFX version string against {@link #MIN_JAVAFX_MAJOR}. Unknown formats are accepted.
     * Package-private so it can be unit tested without a running JavaFX toolkit.
     */
    static boolean checkVersion(String version) {
        try {
            int major = Integer.parseInt(version.split("[.+-]")[0]);
            if (major < MIN_JAVAFX_MAJOR) {
                System.err.println("[fxpilot] JavaFX " + MIN_JAVAFX_MAJOR + " or newer required, found "
                        + version + ", FxPilot disabled");
                return false;
            }
        } catch (NumberFormatException e) {
            System.err.println("[fxpilot] could not parse JavaFX version '" + version + "', starting anyway");
        }
        return true;
    }
}
