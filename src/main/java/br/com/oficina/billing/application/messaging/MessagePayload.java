package br.com.oficina.billing.application.messaging;

import br.com.oficina.billing.domain.valueobject.Money;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * Leitura tipada e validada do {@code payload} de uma mensagem recebida. Campo obrigatório ausente ou de tipo
 * errado é mensagem malformada: {@link InvalidMessageException}, que leva a mensagem à DLQ depois das reentregas,
 * em vez de cobrar o cliente com dados inventados.
 */
public final class MessagePayload {

    private final Map<String, Object> values;

    public MessagePayload(Map<String, Object> values) {
        this.values = values == null ? Map.of() : Map.copyOf(withoutNulls(values));
    }

    public String text(String field) {
        Object value = required(field);
        if (value instanceof String text && !text.isBlank()) {
            return text;
        }
        throw new InvalidMessageException("Campo '" + field + "' deve ser texto não vazio");
    }

    public int integer(String field) {
        return Math.toIntExact(longInteger(field));
    }

    public long longInteger(String field) {
        Object value = required(field);
        if (value instanceof Number number) {
            return number.longValue();
        }
        throw new InvalidMessageException("Campo '" + field + "' deve ser numérico");
    }

    /** Valor monetário do contrato: texto com duas casas ({@code "450.00"}). */
    public Money money(String field) {
        try {
            return Money.of(text(field));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Campo '" + field + "' não é um valor monetário válido");
        }
    }

    public UUID uuid(String field) {
        try {
            return UUID.fromString(text(field));
        } catch (IllegalArgumentException e) {
            throw new InvalidMessageException("Campo '" + field + "' não é um identificador válido");
        }
    }

    /** Objeto aninhado (ex.: {@code customer}). */
    @SuppressWarnings("unchecked")
    public MessagePayload object(String field) {
        Object value = required(field);
        if (value instanceof Map<?, ?> map) {
            return new MessagePayload((Map<String, Object>) map);
        }
        throw new InvalidMessageException("Campo '" + field + "' deve ser um objeto");
    }

    /** Lista de objetos (ex.: {@code items}). */
    @SuppressWarnings("unchecked")
    public List<MessagePayload> objects(String field) {
        Object value = required(field);
        if (value instanceof List<?> list && list.stream().allMatch(Map.class::isInstance)) {
            return list.stream().map(item -> new MessagePayload((Map<String, Object>) item)).toList();
        }
        throw new InvalidMessageException("Campo '" + field + "' deve ser uma lista de objetos");
    }

    public Optional<String> optionalText(String field) {
        return Optional.ofNullable(values.get(field)).map(Object::toString);
    }

    private Object required(String field) {
        Object value = values.get(field);
        if (value == null) {
            throw new InvalidMessageException("Campo obrigatório ausente: '" + field + "'");
        }
        return value;
    }

    private static Map<String, Object> withoutNulls(Map<String, Object> source) {
        Map<String, Object> copy = new HashMap<>(source);
        copy.values().removeIf(Objects::isNull);
        return copy;
    }
}
