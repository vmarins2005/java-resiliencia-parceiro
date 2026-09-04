package io.github.vmarins2005.checkout;

/**
 * A cobrança que precisa de um parecer do parceiro antes de ser autorizada.
 *
 * <p>O {@code id} é gerado por nós, e não pelo parceiro. Isso é o que permite reenviar a
 * mesma cobrança sem duplicá-la — ver ADR 0002.
 */
public record Cobranca(String id, long valorEmCentavos, String cpf) {

    public Cobranca {
        if (id == null || id.isBlank()) {
            throw new IllegalArgumentException("cobrança sem id não pode ser reenviada com segurança");
        }
        if (valorEmCentavos <= 0) {
            throw new IllegalArgumentException("valor deve ser positivo: " + valorEmCentavos);
        }
    }

    public static Cobranca de(String id, long valorEmCentavos) {
        return new Cobranca(id, valorEmCentavos, "12345678901");
    }
}
