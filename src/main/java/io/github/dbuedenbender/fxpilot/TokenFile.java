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

import io.github.dbuedenbender.fxpilot.internal.TokenFiles;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.PosixFilePermissions;
import java.security.SecureRandom;
import java.util.Base64;

/**
 * Token exchange for {@code fxpilot.token=auto}: the endpoint generates a random token and writes it to
 * {@code <dir>/<port>.token}, where the MCP server reads it. The directory is per user
 * ({@code %LOCALAPPDATA%\FxPilot\ports} on Windows, {@code ~/.fxpilot/ports} elsewhere), and on POSIX
 * systems the file is readable by the owner only. {@code fxpilot.tokenDir} / {@code FXPILOT_TOKEN_DIR}
 * override the directory.
 *
 * <p>The directory itself comes from {@link TokenFiles}, which the MCP server uses as well.
 */
final class TokenFile {

    static final String AUTO = "auto";

    private TokenFile() {
    }

    static String generate() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }

    static Path path(int port) {
        return TokenFiles.file(port);
    }

    static Path write(int port, String token) throws IOException {
        Path file = path(port);
        Files.createDirectories(file.getParent());
        Files.deleteIfExists(file);
        if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
            Files.createFile(file, PosixFilePermissions.asFileAttribute(PosixFilePermissions.fromString("rw-------")));
        } else {
            Files.createFile(file);
        }
        Files.write(file, token.getBytes(StandardCharsets.UTF_8));
        return file;
    }

    static void delete(int port) {
        try {
            Files.deleteIfExists(path(port));
        } catch (IOException e) {
            System.err.println("[fxpilot] could not delete token file: " + e);
        }
    }
}
