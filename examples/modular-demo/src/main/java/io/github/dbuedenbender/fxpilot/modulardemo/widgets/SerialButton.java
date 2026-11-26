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
package io.github.dbuedenbender.fxpilot.modulardemo.widgets;

import javafx.scene.control.Button;

/** Custom control whose package is exported to FxPilot, so {@code fx_property name=serial} can read it. */
public class SerialButton extends Button {

    private final String serial;

    public SerialButton(String text, String serial) {
        super(text);
        this.serial = serial;
    }

    public String getSerial() {
        return serial;
    }
}
