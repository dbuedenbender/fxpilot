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
import javafx.scene.SnapshotParameters;
import javafx.scene.image.Image;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;
import javafx.stage.Window;

import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import javax.imageio.ImageIO;

/**
 * PNG encoding of window, node and image snapshots.
 */
final class Screenshots {

    private Screenshots() {
    }

    static byte[] screenshot(Window w, Node node) throws IOException {
        return screenshot(w, node, 0, 0, -1, -1);
    }

    static byte[] screenshot(Window w, Node node, double x, double y, double width, double height) throws IOException {
        WritableImage img = FxThread.query(() -> node != null
                ? node.snapshot(new SnapshotParameters(), null)
                : w.getScene().snapshot(null));
        int imageWidth = (int) img.getWidth();
        int imageHeight = (int) img.getHeight();
        int clipX = Math.max(0, Math.min(imageWidth, (int) Math.round(x)));
        int clipY = Math.max(0, Math.min(imageHeight, (int) Math.round(y)));
        int clipWidth = width > 0 ? Math.min(imageWidth - clipX, (int) Math.round(width)) : imageWidth - clipX;
        int clipHeight = height > 0 ? Math.min(imageHeight - clipY, (int) Math.round(height)) : imageHeight - clipY;
        if (clipWidth <= 0 || clipHeight <= 0) {
            throw new FxPilotException(400, Codes.INVALID_VALUE, "screenshot region is empty");
        }
        int[] argb = new int[clipWidth * clipHeight];
        img.getPixelReader().getPixels(clipX, clipY, clipWidth, clipHeight,
                PixelFormat.getIntArgbInstance(), argb, 0, clipWidth);
        BufferedImage buffered = new BufferedImage(clipWidth, clipHeight, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, clipWidth, clipHeight, argb, 0, clipWidth);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(buffered, "png", out);
        return out.toByteArray();
    }

    static BufferedImage toBufferedImage(Image image) {
        int width = (int) image.getWidth();
        int height = (int) image.getHeight();
        int[] argb = new int[width * height];
        image.getPixelReader().getPixels(0, 0, width, height, PixelFormat.getIntArgbInstance(), argb, 0, width);
        BufferedImage buffered = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        buffered.setRGB(0, 0, width, height, argb, 0, width);
        return buffered;
    }

    static byte[] toPng(Image image) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ImageIO.write(toBufferedImage(image), "png", out);
        return out.toByteArray();
    }
}
