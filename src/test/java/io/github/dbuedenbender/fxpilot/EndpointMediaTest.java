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
import javafx.scene.layout.HBox;
import javafx.stage.Stage;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Files;
import java.nio.file.Path;

import static io.github.dbuedenbender.fxpilot.EndpointHttp.*;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * fx_media against a real MediaView. javafx-media is only on the test runtime class path, so the nodes
 * are created reflectively, the same way the endpoint talks to them. Nothing is played: the test needs
 * no audio device.
 */
class EndpointMediaTest extends UiTestBase {

    private Stage stage;

    @AfterEach
    void closeStage() throws Exception {
        if (stage != null) {
            TestUi.onFx(() -> {
                Object player = Reflect.invokeOptional(stage.getScene().lookup("#media"), "getMediaPlayer");
                if (player != null && player != Reflect.NoValue.INSTANCE) {
                    Reflect.invoke(player, "dispose");
                }
                stage.close();
            });
        }
    }

    /** One second of 8 kHz mono silence. */
    private Path silence() throws Exception {
        int samples = 8000;
        ByteBuffer header = ByteBuffer.allocate(44).order(ByteOrder.LITTLE_ENDIAN);
        header.put("RIFF".getBytes()).putInt(36 + samples * 2).put("WAVE".getBytes())
                .put("fmt ".getBytes()).putInt(16).putShort((short) 1).putShort((short) 1)
                .putInt(8000).putInt(16000).putShort((short) 2).putShort((short) 16)
                .put("data".getBytes()).putInt(samples * 2);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(header.array());
        out.write(new byte[samples * 2]);
        // below target/, not @TempDir: on Windows the player keeps the file open until it is collected
        Path wav = Path.of("target", "media-test", "silence.wav");
        Files.createDirectories(wav.getParent());
        Files.write(wav, out.toByteArray());
        return wav;
    }

    @Test
    void controlsAMediaPlayerAndReportsItsState() throws Exception {
        String uri = silence().toUri().toString();
        Throwable[] nativeProblem = new Throwable[1];
        TestUi.onFx(() -> {
            try {
                Object media = Class.forName("javafx.scene.media.Media").getConstructor(String.class).newInstance(uri);
                Class<?> playerType = Class.forName("javafx.scene.media.MediaPlayer");
                Object player = playerType.getConstructor(media.getClass()).newInstance(media);
                Node view = (Node) Class.forName("javafx.scene.media.MediaView").getConstructor(playerType).newInstance(player);
                view.setId("media");
                Node empty = (Node) Class.forName("javafx.scene.media.MediaView").getConstructor().newInstance();
                empty.setId("emptyMedia");
                stage = new Stage();
                stage.setTitle("Media");
                stage.setScene(new Scene(new HBox(view, empty), 200, 100));
                stage.show();
            } catch (java.lang.reflect.InvocationTargetException e) {
                // MediaException or a missing native library: the media stack is not usable on this machine
                nativeProblem[0] = e.getCause();
            } catch (ReflectiveOperationException e) {
                throw new IllegalStateException(e);
            } catch (LinkageError e) {
                nativeProblem[0] = e;
            }
        });
        Assumptions.assumeTrue(nativeProblem[0] == null, "javafx-media not available here: " + nativeProblem[0]);

        ok("/exec", "op", "media", "selector", "media", "action", "volume", "value", "0.25");
        ok("/exec", "op", "media", "selector", "media", "action", "mute", "value", "true");
        ok("/exec", "op", "media", "selector", "media", "action", "rate", "value", "1.5");
        String state = ok("/exec", "op", "mediaStatus", "selector", "media");
        assertTrue(state.contains("\"mediaVolume\":0.25"), state);
        assertTrue(state.contains("\"mediaMute\":true"), state);
        assertTrue(state.contains("\"mediaRate\":1.5"), state);

        assertError(post("/exec", "op", "media", "selector", "emptyMedia", "action", "play"), 400, "MEDIA_NOT_AVAILABLE");
    }
}
