package io.github.vmarins2005.checkout;

/** O parceiro respondeu 4xx: o pedido está errado. Retentar não conserta nada. */
public class CobrancaRejeitadaException extends RuntimeException {

    private final int status;

    public CobrancaRejeitadaException(int status, String corpo) {
        super("parceiro rejeitou a cobrança (HTTP " + status + "): " + corpo);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
