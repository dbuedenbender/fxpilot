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
 * Switch for the dangerous operations ({@code fxpilot.dangerous}): JavaScript in a WebView, reflective
 * property setters, file writes and closing windows. Allowed by default in 1.x.
 */
final class Policy {

    private static volatile boolean dangerousAllowed = true;

    private Policy() {
    }

    static void setDangerousAllowed(boolean allowed) {
        dangerousAllowed = allowed;
    }

    static boolean dangerousAllowed() {
        return dangerousAllowed;
    }

    static void requireDangerous(String what) {
        if (!dangerousAllowed) {
            throw new FxPilotException(403, Codes.DANGEROUS_DISABLED, what + " is disabled (fxpilot.dangerous=false)");
        }
    }
}
