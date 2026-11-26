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

/**
 * Modular JavaFX app with FxPilot as a module dependency.
 *
 * <ul>
 *   <li>{@code requires io.github.dbuedenbender.fxpilot} gives access to {@code FxPilotServer}; FxPilot itself
 *       brings {@code jdk.httpserver}, so the app does not have to require it.</li>
 *   <li>FxPilot reads controls through the public JavaFX API. Only {@code fx_property} calls getters of the
 *       app's own classes by reflection, so a package with custom controls is exported to FxPilot. The
 *       package {@code secret} is not, and FxPilot answers PROPERTY_NOT_FOUND for its custom getters.</li>
 *   <li>{@code opens ... to javafx.graphics} lets JavaFX instantiate the Application subclass.</li>
 * </ul>
 */
module io.github.dbuedenbender.fxpilot.modulardemo {
    requires javafx.controls;
    requires io.github.dbuedenbender.fxpilot;

    opens io.github.dbuedenbender.fxpilot.modulardemo to javafx.graphics;
    exports io.github.dbuedenbender.fxpilot.modulardemo.widgets to io.github.dbuedenbender.fxpilot;
}
