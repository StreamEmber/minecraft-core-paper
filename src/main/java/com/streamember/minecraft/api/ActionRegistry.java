package com.streamember.minecraft.api;

import java.util.Map;
import java.util.Objects;

/**
 * Modülün aksiyon işleyicilerini toplar. Aksiyon id'leri {@code game-schema.json} içindeki
 * {@code actions[].id} ile birebir aynı olmalıdır; çekirdek eksik / fazla olanları açılışta uyarır.
 */
public final class ActionRegistry {

    private final Map<String, ActionHandler> handlers;

    public ActionRegistry(Map<String, ActionHandler> handlers) {
        this.handlers = handlers;
    }

    public ActionRegistry on(String actionId, ActionHandler handler) {
        Objects.requireNonNull(actionId, "actionId");
        Objects.requireNonNull(handler, "handler");
        if (handlers.putIfAbsent(actionId, handler) != null) {
            throw new IllegalArgumentException("Action '" + actionId + "' is registered twice.");
        }
        return this;
    }
}
