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

import javafx.scene.input.KeyCode;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class InputOpsTest {

    @Test
    void parsesKnownModifiers() {
        InputOps.Modifiers modifiers = InputOps.parseModifiers("CTRL+SHIFT+ALT+META");
        assertTrue(modifiers.control);
        assertTrue(modifiers.shift);
        assertTrue(modifiers.alt);
        assertTrue(modifiers.meta);

        assertFalse(InputOps.parseModifiers("").control);
    }

    @Test
    void toleratesSurroundingWhitespaceInModifiers() {
        assertTrue(InputOps.parseModifiers(" CTRL").control);
        assertTrue(InputOps.parseModifiers("CTRL + SHIFT ").shift);
    }

    @Test
    void keysTypeTheirCharacterNotTheirName() {
        assertEquals("1", InputOps.typedCharacter(KeyCode.DIGIT1, false));
        assertEquals("7", InputOps.typedCharacter(KeyCode.NUMPAD7, false));
        assertEquals("a", InputOps.typedCharacter(KeyCode.A, false));
        assertEquals("A", InputOps.typedCharacter(KeyCode.A, true));
        assertEquals(" ", InputOps.typedCharacter(KeyCode.SPACE, false));
        assertNull(InputOps.typedCharacter(KeyCode.DIGIT1, true), "SHIFT+digit depends on the keyboard layout");
        assertNull(InputOps.typedCharacter(KeyCode.ENTER, false));
    }

    @Test
    void rejectsUnknownModifiers() {
        assertThrows(FxPilotException.class, () -> InputOps.parseModifiers("CTLR+S"));
        assertThrows(FxPilotException.class, () -> InputOps.parseModifiers("STRG+S"));
    }

    @Test
    void rejectsAHotkeyWithoutAKey() {
        assertThrows(FxPilotException.class, () -> InputOps.hotkey(Map.of("keys", "CTRL+")));
    }
}
