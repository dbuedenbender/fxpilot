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
