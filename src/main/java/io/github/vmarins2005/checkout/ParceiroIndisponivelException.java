package io.github.vmarins2005.checkout;

/**
 * Falha transitória do parceiro: timeout, erro de rede, 5xx, disjuntor aberto, compartimento
 * cheio.
 *
 * <p>É a única exceção que a política de resiliência retenta. A separação entre esta e
 * {@link CobrancaRejeitadaException} é o que impede o retry de martelar o parceiro com um
 * pedido que ele já disse que está errado.
 */
public class ParceiroIndisponivelException extends RuntimeException {

    public ParceiroIndisponivelException(String motivo) {
        super(motivo);
    }

    public ParceiroIndisponivelException(String motivo, Throwable causa) {
        super(motivo, causa);
    }
}
