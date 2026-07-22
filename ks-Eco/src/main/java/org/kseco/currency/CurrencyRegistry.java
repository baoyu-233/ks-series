package org.kseco.currency;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/** Immutable registry that can be atomically replaced after a configuration reload. */
public final class CurrencyRegistry {
    private final CurrencyId cashCurrency;
    private final Map<CurrencyId, CurrencyDefinition> definitions;

    public CurrencyRegistry(CurrencyId cashCurrency, Collection<CurrencyDefinition> definitions) {
        this.cashCurrency = Objects.requireNonNull(cashCurrency, "cashCurrency");
        Objects.requireNonNull(definitions, "definitions");
        Map<CurrencyId, CurrencyDefinition> indexed = new LinkedHashMap<>();
        for (CurrencyDefinition definition : definitions) {
            Objects.requireNonNull(definition, "definition");
            if (indexed.putIfAbsent(definition.id(), definition) != null) {
                throw new IllegalArgumentException("Duplicate currency definition: " + definition.id());
            }
        }
        if (!indexed.containsKey(cashCurrency)) {
            throw new IllegalArgumentException("Cash currency is not defined: " + cashCurrency);
        }
        this.definitions = Map.copyOf(indexed);
    }

    public CurrencyId cashCurrency() {
        return cashCurrency;
    }

    public CurrencyDefinition require(CurrencyId id) {
        CurrencyDefinition definition = definitions.get(Objects.requireNonNull(id, "id"));
        if (definition == null) {
            throw new IllegalArgumentException("Unknown currency: " + id);
        }
        return definition;
    }

    public Optional<CurrencyDefinition> find(CurrencyId id) {
        return Optional.ofNullable(definitions.get(Objects.requireNonNull(id, "id")));
    }

    public List<CurrencyDefinition> definitions() {
        return List.copyOf(definitions.values());
    }

    public boolean isCash(CurrencyId id) {
        return cashCurrency.equals(id);
    }
}
