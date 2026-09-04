package io.github.vmarins2005.checkout;

import java.util.function.Function;

/**
 * O caso de uso do checkout.
 *
 * <p>Ele <b>nunca lança</b>. O critério de conclusão do projeto é que, com o parceiro 100%
 * fora do ar, este método continue respondendo dentro do SLO — e uma exceção subindo daqui
 * seria, na prática, o checkout inteiro fora do ar junto com o parceiro.
 */
public final class Autorizador {

    /** Acima disto, o risco é alto demais para aprovar automaticamente. */
    private static final int LIMITE_DE_RISCO = 30;

    private final Function<Cobranca, Parecer> analiseProtegida;

    public Autorizador(Function<Cobranca, Parecer> analiseProtegida) {
        this.analiseProtegida = analiseProtegida;
    }

    public Decisao autorizar(Cobranca cobranca) {
        try {
            int score = analiseProtegida.apply(cobranca).score();
            return score <= LIMITE_DE_RISCO
                    ? new Decisao.Aprovada(score)
                    : new Decisao.Recusada(score);
        } catch (ParceiroIndisponivelException e) {
            // Sem parecer não dá para aprovar nem recusar com fundamento: a cobrança vai
            // para a fila humana. Ver ADR 0003 para por que não é "aprova" nem "recusa".
            return new Decisao.RevisaoManual(e.getMessage());
        } catch (CobrancaRejeitadaException e) {
            // Não é indisponibilidade: o parceiro entendeu e disse que o pedido está errado.
            return new Decisao.RevisaoManual(e.getMessage());
        }
    }
}
