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
package io.github.buedi.fxpilot;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class FxPilotEngineTest {

    @Test
    void normalizeAddsHashForBareLowercaseId() {
        assertEquals("#okButton", FxPilotEngine.normalize("okButton"));
        assertEquals("#save_2", FxPilotEngine.normalize("save_2"));
    }

    @Test
    void normalizeKeepsCssSelectors() {
        assertEquals(".button", FxPilotEngine.normalize(".button"));
        assertEquals("#root", FxPilotEngine.normalize("#root"));
    }

    @Test
    void normalizeKeepsTypeSelectors() {
        assertEquals("Button", FxPilotEngine.normalize("Button"));
    }

    @Test
    void normalizeTrimsWhitespace() {
        assertEquals("#okButton", FxPilotEngine.normalize("  okButton  "));
    }

    @Test
    void normalizeKeepsTextSelector() {
        assertEquals("text=Save", FxPilotEngine.normalize("text=Save"));
    }

    @Test
    void normalizeKeepsRoleSelector() {
        assertEquals("role=button", FxPilotEngine.normalize("role=button"));
        assertEquals("role=textbox", FxPilotEngine.normalize("role=textbox"));
    }

    @Test
    void normalizeKeepsSubstringTextSelector() {
        assertEquals("text*=Sav", FxPilotEngine.normalize("text*=Sav"));
    }

    @Test
    void normalizeKeepsExactIdSelector() {
        assertEquals("id=customer.name", FxPilotEngine.normalize("id=customer.name"));
        assertEquals("id=customer.name@2", FxPilotEngine.normalize("id=customer.name@2"));
    }

    @Test
    void normalizeKeepsAccessibilityAndReferenceSelectors() {
        assertEquals("accessible=Save", FxPilotEngine.normalize("accessible=Save"));
        assertEquals("accessible*=Sav", FxPilotEngine.normalize("accessible*=Sav"));
        assertEquals("label=Name", FxPilotEngine.normalize("label=Name"));
        assertEquals("ref=VBox[0]/Button[1]", FxPilotEngine.normalize("ref=VBox[0]/Button[1]"));
        assertEquals("path=VBox[0]/Button[1]", FxPilotEngine.normalize("path=VBox[0]/Button[1]"));
    }

    @Test
    void parseModifiersRecognizesCommonNames() {
        FxPilotExtensions.Modifiers mods = FxPilotExtensions.parseModifiers("CTRL+SHIFT");
        assertTrue(mods.control);
        assertTrue(mods.shift);
        assertFalse(mods.alt);
        assertFalse(mods.meta);
        assertFalse(FxPilotExtensions.parseModifiers("").control);
    }

    @Test
    void parseIdSelectorSplitsOptionalMatchIndex() {
        FxPilotEngine.IdSpec plain = FxPilotEngine.parseIdSelector("customer.name");
        assertEquals("customer.name", plain.id);
        assertNull(plain.index);

        FxPilotEngine.IdSpec indexed = FxPilotEngine.parseIdSelector("customer.name@2");
        assertEquals("customer.name", indexed.id);
        assertEquals("2", indexed.index);

        FxPilotEngine.IdSpec atInName = FxPilotEngine.parseIdSelector("mail@home");
        assertEquals("mail@home", atInName.id);
        assertNull(atInName.index);
    }

    @Test
    void pickSelectsByIndexOrValue() {
        List<String> items = List.of("alpha", "beta");
        assertEquals("alpha", FxPilotEngine.pick(items, null, "0"));
        assertEquals("beta", FxPilotEngine.pick(items, null, " 1 "));
        assertNull(FxPilotEngine.pick(items, null, "2"));
        assertEquals("beta", FxPilotEngine.pick(items, "beta", null));
        assertNull(FxPilotEngine.pick(items, "gamma", null));
    }

    @Test
    void normalizeRejectsNullOrBlank() {
        assertThrows(FxPilotEngine.FxPilotException.class, () -> FxPilotEngine.normalize(null));
        assertThrows(FxPilotEngine.FxPilotException.class, () -> FxPilotEngine.normalize("   "));
    }
}
