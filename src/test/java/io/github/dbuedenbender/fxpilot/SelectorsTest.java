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

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SelectorsTest {

    @Test
    void normalizeAddsHashForBareLowercaseId() {
        assertEquals("#okButton", Selectors.normalize("okButton"));
        assertEquals("#save_2", Selectors.normalize("save_2"));
    }

    @Test
    void normalizeKeepsCssSelectors() {
        assertEquals(".button", Selectors.normalize(".button"));
        assertEquals("#root", Selectors.normalize("#root"));
    }

    @Test
    void normalizeKeepsTypeSelectors() {
        assertEquals("Button", Selectors.normalize("Button"));
    }

    @Test
    void normalizeTrimsWhitespace() {
        assertEquals("#okButton", Selectors.normalize("  okButton  "));
    }

    @Test
    void normalizeKeepsTextSelector() {
        assertEquals("text=Save", Selectors.normalize("text=Save"));
    }

    @Test
    void normalizeKeepsRoleSelector() {
        assertEquals("role=button", Selectors.normalize("role=button"));
        assertEquals("role=textbox", Selectors.normalize("role=textbox"));
    }

    @Test
    void normalizeKeepsSubstringTextSelector() {
        assertEquals("text*=Sav", Selectors.normalize("text*=Sav"));
    }

    @Test
    void normalizeKeepsExactIdSelector() {
        assertEquals("id=customer.name", Selectors.normalize("id=customer.name"));
        assertEquals("id=customer.name@2", Selectors.normalize("id=customer.name@2"));
    }

    @Test
    void normalizeKeepsAccessibilityAndReferenceSelectors() {
        assertEquals("accessible=Save", Selectors.normalize("accessible=Save"));
        assertEquals("accessible*=Sav", Selectors.normalize("accessible*=Sav"));
        assertEquals("label=Name", Selectors.normalize("label=Name"));
        assertEquals("ref=VBox[0]/Button[1]", Selectors.normalize("ref=VBox[0]/Button[1]"));
        assertEquals("path=VBox[0]/Button[1]", Selectors.normalize("path=VBox[0]/Button[1]"));
    }

    @Test
    void parseModifiersRecognizesCommonNames() {
        InputOps.Modifiers mods = InputOps.parseModifiers("CTRL+SHIFT");
        assertTrue(mods.control);
        assertTrue(mods.shift);
        assertFalse(mods.alt);
        assertFalse(mods.meta);
        assertFalse(InputOps.parseModifiers("").control);
    }

    @Test
    void parseIdSelectorSplitsOptionalMatchIndex() {
        Selectors.IdSpec plain = Selectors.parseIdSelector("customer.name");
        assertEquals("customer.name", plain.id);
        assertNull(plain.index);

        Selectors.IdSpec indexed = Selectors.parseIdSelector("customer.name@2");
        assertEquals("customer.name", indexed.id);
        assertEquals("2", indexed.index);

        Selectors.IdSpec atInName = Selectors.parseIdSelector("mail@home");
        assertEquals("mail@home", atInName.id);
        assertNull(atInName.index);
    }

    @Test
    void pickSelectsByIndexOrValue() {
        List<String> items = List.of("alpha", "beta");
        assertEquals("alpha", Actions.pick(items, null, "0"));
        assertEquals("beta", Actions.pick(items, null, " 1 "));
        assertNull(Actions.pick(items, null, "2"));
        assertEquals("beta", Actions.pick(items, "beta", null));
        assertNull(Actions.pick(items, "gamma", null));
    }

    @Test
    void normalizeRejectsNullOrBlank() {
        assertThrows(FxPilotException.class, () -> Selectors.normalize(null));
        assertThrows(FxPilotException.class, () -> Selectors.normalize("   "));
    }
}
