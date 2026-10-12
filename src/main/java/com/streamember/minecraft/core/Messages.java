package com.streamember.minecraft.core;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;

/** Komut çıktıları için küçük Adventure yardımcıları. */
final class Messages {

    private Messages() {
    }

    static TextComponent prefix() {
        return Component.text("[StreamEmber] ", NamedTextColor.GOLD);
    }

    static Component text(String text) {
        return Component.text(text, NamedTextColor.GRAY);
    }

    static Component ok(String text) {
        return prefix().append(Component.text(text, NamedTextColor.GREEN));
    }

    static Component error(String text) {
        return prefix().append(Component.text(text, NamedTextColor.RED));
    }

    static Component info(String text) {
        return prefix().append(text(text));
    }

    static Component row(String label, String value, boolean good) {
        return Component.text("  " + label + ": ", NamedTextColor.GRAY)
                .append(Component.text(value, good ? NamedTextColor.WHITE : NamedTextColor.YELLOW));
    }
}
