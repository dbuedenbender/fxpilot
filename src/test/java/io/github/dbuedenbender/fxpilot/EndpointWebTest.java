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

import javafx.scene.Node;
import javafx.scene.Scene;
import javafx.scene.layout.StackPane;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx_web against a real WebView. javafx-web is only on the test runtime class path, so the WebView is
 * created reflectively, the same way the endpoint talks to it.
 */
class EndpointWebTest extends UiTestBase {

    private Stage stage;

    @AfterEach
    void closeStage() throws Exception {
        if (stage != null) {
            TestUi.onFx(stage::close);
        }
    }

    private void showPage(String html) throws Exception {
        Throwable[] nativeProblem = new Throwable[1];
        TestUi.onFx(() -> {
            try {
                Node web = (Node) Class.forName("javafx.scene.web.WebView").getConstructor().newInstance();
                web.setId("web");
                Object engine = Reflect.invoke(web, "getEngine");
                Reflect.invoke(engine, "loadContent", html);
                stage = new Stage();
                stage.setTitle("Web");
                stage.setScene(new Scene(new StackPane(web), 400, 300));
                stage.show();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            } catch (LinkageError e) {
                nativeProblem[0] = e; // WebKit's native libraries are missing on this machine
            }
        });
        Assumptions.assumeTrue(nativeProblem[0] == null, "WebView not available here: " + nativeProblem[0]);
        long end = System.currentTimeMillis() + 10_000;
        while (System.currentTimeMillis() < end) {
            String state = FxThread.query(() -> String.valueOf(((javafx.concurrent.Worker<?>) Reflect.invoke(
                    Reflect.invoke(Selectors.find("web"), "getEngine"), "getLoadWorker")).getState()));
            if ("SUCCEEDED".equals(state)) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("page did not load");
    }

    @Test
    void readsQueriesClicksAndRunsScriptsInAWebView() throws Exception {
        showPage("<html><body><p id='msg'>hello</p>"
                + "<button id='b' onclick=\"document.getElementById('msg').textContent='clicked'\">B</button></body></html>");

        String html = ok("/exec", "op", "webHtml", "selector", "web");
        assertTrue(html.contains("hello"), html);
        String query = ok("/exec", "op", "webQuery", "selector", "web", "css", "#msg", "property", "textContent");
        assertTrue(query.contains("\"value\":\"hello\""), query);

        ok("/exec", "op", "webClick", "selector", "web", "css", "#b");
        String after = ok("/exec", "op", "webQuery", "selector", "web", "css", "#msg", "property", "textContent");
        assertTrue(after.contains("\"value\":\"clicked\""), after);

        String result = ok("/exec", "op", "webExecute", "selector", "web", "script", "6*7");
        assertTrue(result.contains("\"result\":\"42\""), result);
    }
}
