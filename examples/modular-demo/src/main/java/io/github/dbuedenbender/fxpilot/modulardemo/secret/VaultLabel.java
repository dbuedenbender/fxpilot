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
package io.github.dbuedenbender.fxpilot.modulardemo.secret;

import javafx.scene.control.Label;

/**
 * Custom control in a package that is not exported. FxPilot still sees it as a Label (public JavaFX API),
 * but cannot call {@link #getCode()} by reflection.
 */
public class VaultLabel extends Label {

    private final String code;

    public VaultLabel(String text, String code) {
        super(text);
        this.code = code;
    }

    public String getCode() {
        return code;
    }
}
