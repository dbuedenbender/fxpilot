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

/**
 * Stable, machine-readable error codes. Agents can branch on {@code code} instead of parsing
 * messages, which are meant for humans and may change.
 */
final class Codes {

    private Codes() {
    }

    // transport, auth and protocol
    static final String BAD_REQUEST = "BAD_REQUEST";
    static final String INVALID_NUMBER = "INVALID_NUMBER";
    static final String UNAUTHORIZED = "UNAUTHORIZED";
    static final String FORBIDDEN = "FORBIDDEN";
    static final String READ_ONLY = "READ_ONLY";
    static final String NODE_DENIED = "NODE_DENIED";
    static final String UNKNOWN_ENDPOINT = "UNKNOWN_ENDPOINT";
    static final String METHOD_NOT_ALLOWED = "METHOD_NOT_ALLOWED";
    static final String BODY_TOO_LARGE = "BODY_TOO_LARGE";
    static final String CONFLICT = "CONFLICT";
    static final String INTERNAL_ERROR = "INTERNAL_ERROR";

    // selectors and lookups
    static final String INVALID_SELECTOR = "INVALID_SELECTOR";
    static final String NODE_NOT_FOUND = "NODE_NOT_FOUND";
    static final String NODE_AMBIGUOUS = "NODE_AMBIGUOUS";
    static final String WINDOW_NOT_FOUND = "WINDOW_NOT_FOUND";
    static final String ITEM_NOT_FOUND = "ITEM_NOT_FOUND";
    static final String ROW_NOT_FOUND = "ROW_NOT_FOUND";
    static final String TAB_NOT_FOUND = "TAB_NOT_FOUND";
    static final String MENU_NOT_FOUND = "MENU_NOT_FOUND";
    static final String TREE_ITEM_NOT_FOUND = "TREE_ITEM_NOT_FOUND";

    // actions
    static final String UNSUPPORTED_NODE = "UNSUPPORTED_NODE";
    static final String NOT_SCROLLABLE = "NOT_SCROLLABLE";
    static final String INVALID_KEY = "INVALID_KEY";
    static final String INVALID_VALUE = "INVALID_VALUE";
    static final String NODE_DISABLED = "NODE_DISABLED";
    static final String NO_CHILDREN = "NO_CHILDREN";
    static final String ACTION_FAILED = "ACTION_FAILED";
    static final String INTERRUPTED = "INTERRUPTED";
    static final String NODE_HIDDEN = "NODE_HIDDEN";
    static final String NODE_OFFSCREEN = "NODE_OFFSCREEN";
    static final String UNSUPPORTED_ACTION = "UNSUPPORTED_ACTION";
    static final String PROPERTY_NOT_FOUND = "PROPERTY_NOT_FOUND";
    static final String WINDOW_ACTION_FAILED = "WINDOW_ACTION_FAILED";
    static final String WEB_NOT_AVAILABLE = "WEB_NOT_AVAILABLE";
    static final String CHART_NOT_FOUND = "CHART_NOT_FOUND";
    static final String CANVAS_NOT_FOUND = "CANVAS_NOT_FOUND";
    static final String MEDIA_NOT_AVAILABLE = "MEDIA_NOT_AVAILABLE";
    static final String IMAGE_NOT_FOUND = "IMAGE_NOT_FOUND";
    static final String INVALID_OPERATION = "INVALID_OPERATION";

    // runtime
    static final String TOOLKIT_NOT_RUNNING = "TOOLKIT_NOT_RUNNING";
    static final String FX_THREAD_TIMEOUT = "FX_THREAD_TIMEOUT";

    /** Fallback code for exceptions that were thrown without an explicit one. */
    static String forStatus(int status) {
        switch (status) {
            case 400: return BAD_REQUEST;
            case 401: return UNAUTHORIZED;
            case 403: return FORBIDDEN;
            case 404: return NODE_NOT_FOUND;
            case 405: return METHOD_NOT_ALLOWED;
            case 409: return CONFLICT;
            case 413: return BODY_TOO_LARGE;
            case 503: return TOOLKIT_NOT_RUNNING;
            case 504: return FX_THREAD_TIMEOUT;
            default: return status >= 500 ? INTERNAL_ERROR : BAD_REQUEST;
        }
    }
}