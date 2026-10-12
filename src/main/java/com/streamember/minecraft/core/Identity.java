package com.streamember.minecraft.core;

import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Yayıncının müşteri UUID'si ve nereden geldiği.
 *
 * <p>Sıra: {@code config.yml customer-uuid} (elle verildiyse) → launcher yerel API'si
 * ({@code GET <launcher-url>/api/customer}) → {@code STREAMEMBER_CUSTOMER_UUID} ortam değişkeni.</p>
 */
record Identity(String customerUuid, Source source) {

    enum Source { CONFIG, LAUNCHER, ENVIRONMENT }

    static final String ENVIRONMENT_VARIABLE = "STREAMEMBER_CUSTOMER_UUID";

    static CompletableFuture<Optional<Identity>> resolve(CoreConfig config, Http http) {
        Optional<String> configured = normalize(config.customerUuid());
        if (configured.isPresent()) {
            return CompletableFuture.completedFuture(Optional.of(new Identity(configured.get(), Source.CONFIG)));
        }
        return http.getJson(config.launcherUrl() + "/api/customer")
                .thenApply(json -> normalize(json.has("customerUuid") && json.get("customerUuid").isJsonPrimitive()
                        ? json.get("customerUuid").getAsString() : null)
                        .map(uuid -> new Identity(uuid, Source.LAUNCHER)))
                .exceptionally(error -> Optional.empty())
                .thenApply(found -> found.or(() -> normalize(System.getenv(ENVIRONMENT_VARIABLE))
                        .map(uuid -> new Identity(uuid, Source.ENVIRONMENT))));
    }

    static Optional<String> normalize(String value) {
        if (value == null || value.isBlank()) return Optional.empty();
        try {
            return Optional.of(UUID.fromString(value.trim()).toString().toLowerCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            return Optional.empty();
        }
    }
}
