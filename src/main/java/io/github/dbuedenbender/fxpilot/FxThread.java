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

import javafx.application.Platform;
import javafx.scene.Node;

import java.util.*;
import java.util.concurrent.*;
import java.util.function.Consumer;

/**
 * Runs scene-graph work on the FX thread and waits for it with a timeout. Actions that block the
 * FX thread (e.g. a handler that opens a modal dialog) return {@code status: pending}.
 */
final class FxThread {

    private static final long QUERY_TIMEOUT_MS = 10_000;
    private static final long ACTION_WAIT_MS = 2_000;

    private FxThread() {
    }

    static <T> T query(Callable<T> task) {
        if (Platform.isFxApplicationThread()) {
            try {
                return task.call();
            } catch (RuntimeException e) {
                throw e;
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        }
        CompletableFuture<T> future = new CompletableFuture<>();
        runLater(() -> {
            try {
                future.complete(task.call());
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        try {
            return future.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            throw new FxPilotException(504, Codes.FX_THREAD_TIMEOUT, "FX thread did not respond within " + QUERY_TIMEOUT_MS + " ms");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FxPilotException(500, Codes.INTERRUPTED, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof RuntimeException) {
                throw (RuntimeException) cause;
            }
            throw new RuntimeException(cause);
        }
    }

    /**
     * Runs an action that may block the FX thread (e.g. an event handler opening a modal dialog).
     * Returns after the action finished or after {@link #ACTION_WAIT_MS}, whichever comes first.
     */
    static Map<String, Object> action(String selector, Consumer<Node> action) {
        return action(selector, null, null, action);
    }

    static Map<String, Object> action(String selector, String within, String nth, Consumer<Node> action) {
        return action(selector, within, nth, false, false, action);
    }

    /**
     * Resolves the node, checks it (visible, enabled, ambiguity, deny list) and runs the action in one step
     * on the FX thread, so the checks see the same scene graph the action works on.
     */
    static Map<String, Object> action(String selector, String within, String nth,
            boolean requireVisible, boolean requireEnabled, Consumer<Node> action) {
        return interact(() -> {
            List<Node> all = Selectors.findAll(selector, within);
            boolean disambiguated = nth != null && !nth.isBlank();
            String index = disambiguated ? nth : Selectors.inlineIdIndex(selector);
            disambiguated = disambiguated || index != null;
            Node node = Selectors.pickMatch(selector, all, index);
            if (requireVisible && !Selectors.isBranchVisible(node)) {
                throw new FxPilotException(409, Codes.NODE_HIDDEN, "Node '" + selector + "' is hidden");
            }
            if (requireEnabled && node.isDisabled()) {
                throw new FxPilotException(409, Codes.NODE_DISABLED, "Node '" + selector + "' is disabled");
            }
            if (all.size() > 1 && !disambiguated && Boolean.parseBoolean(System.getProperty("fxpilot.strictSelectors",
                    String.valueOf(System.getenv("FXPILOT_STRICT"))))) {
                throw new FxPilotException(409, Codes.NODE_AMBIGUOUS, "Selector '" + selector + "' matches "
                        + all.size() + " nodes: " + Selectors.describe(all, 5)
                        + ". Use a more specific selector, nth=<index>, or call fx_find to list them.");
            }
            DenyList.checkDenied(selector, node);
            action.accept(node);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "done");
            result.put("node", NodeInfo.info(node));
            if (all.size() > 1 && !disambiguated) {
                result.put("matches", all.size());
                result.put("warning", "Selector matched " + all.size() + " nodes, used the first one. "
                        + "Add #id, a CSS class, text= or nth=<index> to be specific, or call fx_find.");
            }
            return result;
        });
    }

    /** Menu entries are model objects, so they bypass {@link #action} and are fired directly. */
    static Map<String, Object> actionMenu(String selector, String within, String nth, String menu, String item) {
        return interact(() -> Actions.clickMenu(selector, within, nth, menu, item));
    }

    /**
     * Like {@link #query}, for work that fires handlers (menu items, keys, dialog buttons): a handler that
     * opens a modal dialog blocks the FX thread, so this returns {@code status: pending} after
     * {@link #ACTION_WAIT_MS} instead of failing with FX_THREAD_TIMEOUT.
     */
    static Map<String, Object> interact(Callable<Map<String, Object>> task) {
        if (Platform.isFxApplicationThread()) {
            return query(task);
        }
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> result =
                new java.util.concurrent.atomic.AtomicReference<>();
        CompletableFuture<Void> done = new CompletableFuture<>();
        runLater(() -> {
            try {
                result.set(task.call());
                done.complete(null);
            } catch (Throwable t) {
                done.completeExceptionally(t);
            }
        });
        if (awaitAction(done)) {
            return result.get();
        }
        // the caller only sees "pending"; a failure after that would otherwise vanish
        done.whenComplete((ok, error) -> {
            if (error != null) {
                System.err.println("[fxpilot] pending action failed later: " + error);
            }
        });
        return pending();
    }

    private static boolean awaitAction(CompletableFuture<?> done) {
        try {
            done.get(ACTION_WAIT_MS, TimeUnit.MILLISECONDS);
            return true;
        } catch (TimeoutException e) {
            return false;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new FxPilotException(500, Codes.INTERRUPTED, "interrupted");
        } catch (ExecutionException e) {
            Throwable cause = e.getCause();
            if (cause instanceof FxPilotException) {
                throw (FxPilotException) cause;
            }
            if (cause instanceof NumberFormatException) {
                // e.g. index=abc; a client error, not a failing handler
                throw new FxPilotException(400, Codes.INVALID_NUMBER, "invalid number: " + cause.getMessage());
            }
            throw new FxPilotException(500, Codes.ACTION_FAILED, "Action failed: " + cause);
        }
    }

    private static Map<String, Object> pending() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "pending");
        result.put("hint", "The FX thread is still busy, probably a modal dialog or a long-running handler. "
                + "Call list_windows or screenshot to see what is open.");
        return result;
    }

    /** Waits until the FX thread has processed the work that was already queued when the call arrived. */
    static Map<String, Object> idle() {
        if (!Platform.isFxApplicationThread()) {
            CompletableFuture<Void> done = new CompletableFuture<>();
            runLater(() -> done.complete(null));
            try {
                done.get(QUERY_TIMEOUT_MS, TimeUnit.MILLISECONDS);
            } catch (TimeoutException e) {
                throw new FxPilotException(504, Codes.FX_THREAD_TIMEOUT,
                        "FX thread did not become idle within " + QUERY_TIMEOUT_MS + " ms");
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new FxPilotException(500, Codes.INTERRUPTED, "interrupted");
            } catch (ExecutionException e) {
                throw new FxPilotException(500, Codes.INTERNAL_ERROR, "idle wait failed: " + e.getCause());
            }
        }
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("status", "done");
        return result;
    }

    private static void runLater(Runnable r) {
        try {
            Platform.runLater(r);
        } catch (IllegalStateException e) {
            throw new FxPilotException(503, Codes.TOOLKIT_NOT_RUNNING, "JavaFX toolkit is not running (yet): " + e.getMessage());
        }
    }
}
