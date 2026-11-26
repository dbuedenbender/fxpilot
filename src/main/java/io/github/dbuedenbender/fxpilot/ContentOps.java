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

import javafx.event.Event;
import javafx.geometry.Point2D;
import javafx.scene.Node;
import javafx.scene.SnapshotParameters;
import javafx.scene.canvas.Canvas;
import javafx.scene.chart.PieChart;
import javafx.scene.chart.XYChart;
import javafx.scene.control.Control;
import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.WritableImage;
import javafx.scene.input.MouseButton;
import javafx.scene.input.MouseEvent;
import javafx.util.Duration;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.imageio.ImageIO;

/**
 * WebView, charts, canvas, media, images and tooltips.
 */
final class ContentOps {

    private ContentOps() {
    }

    static Map<String, Object> webExecute(Map<String, String> p) {
        return FxThread.interact(() -> {
            Node node = Params.node(p);
            DenyList.checkDenied(p.get("selector"), node);
            Object engine = webEngine(node);
            Object result = Reflect.invoke(engine, "executeScript", Params.req(p, "script"));
            return Params.map("status", "done", "result", result == null ? null : String.valueOf(result));
        });
    }

    static Map<String, Object> webQuery(Map<String, String> p) {
        Policy.requireDangerous("WebView JavaScript");
        return FxThread.query(() -> {
            Node node = Params.node(p);
            Object engine = webEngine(node);
            String css = Params.req(p, "css");
            String property = Params.opt(p, "property", "outerHTML");
            String script = "(function(){var e=document.querySelector(" + jsString(css) + ");"
                    + "if(!e)return null;var v=e[" + jsString(property) + "];"
                    + "return v==null?null:String(v);})()";
            Object result = Reflect.invoke(engine, "executeScript", script);
            return Params.map("status", "done", "selector", css, "property", property,
                    "value", result == null ? null : String.valueOf(result));
        });
    }

    static void webClick(Node node, String css) {
        Policy.requireDangerous("WebView JavaScript");
        Object engine = webEngine(node);
        Reflect.invoke(engine, "executeScript",
                "(function(){var e=document.querySelector(" + jsString(css) + ");if(e)e.click();})()");
    }

    static Map<String, Object> webHtml(Map<String, String> p) {
        Policy.requireDangerous("WebView JavaScript");
        return FxThread.query(() -> {
            Node node = Params.node(p);
            Object engine = webEngine(node);
            Object result = Reflect.invoke(engine, "executeScript", "document.documentElement.outerHTML");
            return Params.map("html", result == null ? null : String.valueOf(result));
        });
    }

    private static Object webEngine(Node node) {
        if (!"javafx.scene.web.WebView".equals(node.getClass().getName())) {
            throw new FxPilotException(400, Codes.WEB_NOT_AVAILABLE, Selectors.typeName(node) + " is not a WebView");
        }
        return Reflect.invoke(node, "getEngine");
    }

    static Map<String, Object> chartData(Map<String, String> p) {
        return FxThread.query(() -> {
            Node node = Params.node(p);
            Map<String, Object> m = new LinkedHashMap<>();
            if (node instanceof XYChart) {
                XYChart<?, ?> chart = (XYChart<?, ?>) node;
                m.put("title", chart.getTitle());
                List<Object> series = new ArrayList<>();
                for (XYChart.Series<?, ?> s : chart.getData()) {
                    Map<String, Object> sm = new LinkedHashMap<>();
                    sm.put("name", s.getName());
                    List<Object> points = new ArrayList<>();
                    for (XYChart.Data<?, ?> d : s.getData()) {
                        points.add(Params.map("x", d.getXValue(), "y", d.getYValue(), "extra", d.getExtraValue()));
                    }
                    sm.put("data", points);
                    series.add(sm);
                }
                m.put("series", series);
                return m;
            }
            if (node instanceof PieChart) {
                PieChart chart = (PieChart) node;
                m.put("title", chart.getTitle());
                List<Object> data = new ArrayList<>();
                for (PieChart.Data d : chart.getData()) {
                    data.add(Params.map("name", d.getName(), "value", d.getPieValue()));
                }
                m.put("data", data);
                return m;
            }
            throw new FxPilotException(400, Codes.CHART_NOT_FOUND, Selectors.typeName(node) + " is not a chart");
        });
    }

    static Map<String, Object> canvasPixel(Map<String, String> p) {
        return FxThread.query(() -> {
            Node node = Params.node(p);
            if (!(node instanceof Canvas)) {
                throw new FxPilotException(400, Codes.CANVAS_NOT_FOUND, Selectors.typeName(node) + " is not a Canvas");
            }
            int x = Params.intVal(p, "x", 0);
            int y = Params.intVal(p, "y", 0);
            Canvas canvas = (Canvas) node;
            if (x < 0 || y < 0 || x >= (int) canvas.getWidth() || y >= (int) canvas.getHeight()) {
                throw new FxPilotException(400, Codes.INVALID_VALUE, "Canvas coordinate is outside the canvas");
            }
            WritableImage img = node.snapshot(new SnapshotParameters(), null);
            int argb = img.getPixelReader().getArgb(x, y);
            return Params.map("x", x, "y", y, "argb", argb,
                    "color", String.format("#%08x", argb));
        });
    }

    static void canvasClick(Node node, Map<String, String> p) {
        if (!(node instanceof Canvas)) {
            throw new FxPilotException(400, Codes.CANVAS_NOT_FOUND, Selectors.typeName(node) + " is not a Canvas");
        }
        double x = Params.doubleVal(p, "x", 0);
        double y = Params.doubleVal(p, "y", 0);
        Point2D scene = node.localToScene(x, y);
        Point2D screen = node.localToScreen(x, y);
        Event.fireEvent(node, InputOps.mouseEvent(MouseEvent.MOUSE_PRESSED, scene.getX(), scene.getY(),
                screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                MouseButton.PRIMARY, 1, InputOps.Modifiers.NONE, true));
        Event.fireEvent(node, InputOps.mouseEvent(MouseEvent.MOUSE_RELEASED, scene.getX(), scene.getY(),
                screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                MouseButton.PRIMARY, 1, InputOps.Modifiers.NONE, false));
        Event.fireEvent(node, InputOps.mouseEvent(MouseEvent.MOUSE_CLICKED, scene.getX(), scene.getY(),
                screen == null ? scene.getX() : screen.getX(), screen == null ? scene.getY() : screen.getY(),
                MouseButton.PRIMARY, 1, InputOps.Modifiers.NONE, false));
    }

    static void mediaAction(Node node, Map<String, String> p) {
        Object player = Reflect.invokeOptional(node, "getMediaPlayer");
        if (player == Reflect.NoValue.INSTANCE || player == null) {
            throw new FxPilotException(400, Codes.MEDIA_NOT_AVAILABLE, Selectors.typeName(node) + " has no MediaPlayer");
        }
        String action = Params.opt(p, "action", "status");
        switch (action) {
            case "play": Reflect.invoke(player, "play"); break;
            case "pause": Reflect.invoke(player, "pause"); break;
            case "stop": Reflect.invoke(player, "stop"); break;
            case "seek": Reflect.invoke(player, "seek", Duration.seconds(Params.doubleVal(p, "value", 0))); break;
            case "volume": Reflect.invoke(player, "setVolume", Params.doubleVal(p, "value", 0.5)); break;
            case "mute": Reflect.invoke(player, "setMute", Boolean.parseBoolean(Params.opt(p, "value", "true"))); break;
            case "rate": Reflect.invoke(player, "setRate", Params.doubleVal(p, "value", 1)); break;
            case "status": break;
            default: throw new FxPilotException(400, Codes.INVALID_VALUE, "Unknown media action '" + action + "'");
        }
        // Result is available through the /node response; this action only mutates.
    }

    static Map<String, Object> imageAction(Map<String, String> p) {
        return FxThread.query(() -> {
            Node node = Params.node(p);
            if (!(node instanceof ImageView) || ((ImageView) node).getImage() == null) {
                throw new FxPilotException(400, Codes.IMAGE_NOT_FOUND, Selectors.typeName(node) + " has no image");
            }
            Image image = ((ImageView) node).getImage();
            Path path = Path.of(Params.req(p, "file"));
            if (Files.exists(path) && !Params.bool(p, "overwrite")) {
                throw new FxPilotException(409, Codes.CONFLICT, "File " + path.toAbsolutePath()
                        + " exists; pass overwrite=true to replace it");
            }
            if (path.getParent() != null) {
                Files.createDirectories(path.getParent());
            }
            ImageIO.write(Screenshots.toBufferedImage(image), "png", path.toFile());
            return Params.map("status", "done", "file", path.toAbsolutePath().toString(),
                    "width", image.getWidth(), "height", image.getHeight());
        });
    }

    static Map<String, Object> tooltip(Map<String, String> p) {
        return FxThread.query(() -> {
            Node node = Params.node(p);
            String tooltip = node instanceof Control && ((Control) node).getTooltip() != null
                    ? ((Control) node).getTooltip().getText() : null;
            return Params.map("tooltip", tooltip);
        });
    }

    private static String jsString(String value) {
        return "\"" + value.replace("\\", "\\\\").replace("\"", "\\\"")
                .replace("\n", "\\n").replace("\r", "\\r") + "\"";
    }
}
