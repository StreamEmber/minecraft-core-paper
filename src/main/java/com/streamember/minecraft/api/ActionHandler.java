package com.streamember.minecraft.api;

/** Bir oyun aksiyonunu çalıştırır. Her zaman sunucunun ana thread'inde çağrılır. */
@FunctionalInterface
public interface ActionHandler {
    void handle(GameAction action) throws Exception;
}
