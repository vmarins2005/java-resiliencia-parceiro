package io.github.vmarins2005.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.retry.Retry;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O critério de conclusão do projeto.
 *
 * <p>Com o parceiro 100% fora do ar, cem pedidos de checkout continuam sendo respondidos
 * dentro do SLO de 400 ms — com resposta degradada, sem exceção subindo, e sem thread presa.
 *
 * <p>Três formas de "fora do ar", porque elas falham por caminhos diferentes no cliente HTTP:
 * respondendo erro, ficando mudo, e derrubando a conexão.
 */
class ComOParceiroForaDoArOCheckoutRespondeTest extends ParceiroFalso {

    private static final int PEDIDOS = 100;

    @Test
    @DisplayName("parceiro respondendo 500")
    void parceiroRespondendoErro() {
        rodada("500", ParceiroFalso::parceiroComErro);
    }

    @Test
    @DisplayName("parceiro mudo — aceita a conexão e nunca responde")
    void parceiroMudo() {
        rodada("mudo", () -> parceiroLento(5_000, 10));
    }

    @Test
    @DisplayName("parceiro derrubando a conexão")
    void parceiroDerrubandoConexao() {
        rodada("conexão derrubada", ParceiroFalso::parceiroDerrubandoAConexao);
    }

    private void rodada(String cenario, Runnable comoOParceiroQuebra) {
        comoOParceiroQuebra.run();
        var checkout = new Autorizador(PoliticaDeResiliencia.proteger(
                ParceiroHttp.com(base(), PoliticaDeResiliencia.TIMEOUT_POR_TENTATIVA),
                CircuitBreaker.of("parceiro", PoliticaDeResiliencia.disjuntorPadrao()),
                Retry.of("parceiro", PoliticaDeResiliencia.retentativaPadrao())));

        long[] duracoes = new long[PEDIDOS];
        int degradados = 0;
        int barradosPeloDisjuntor = 0;

        for (int i = 0; i < PEDIDOS; i++) {
            var pedido = Cobranca.de("c-" + i, 5_000);
            var medido = Cronometro.mede(() -> checkout.autorizar(pedido));
            duracoes[i] = medido.milissegundos();

            if (medido.valor() instanceof Decisao.RevisaoManual revisao) {
                degradados++;
                if (revisao.motivo().contains("disjuntor aberto")) {
                    barradosPeloDisjuntor++;
                }
            }
        }

        System.out.printf("[%s] p50=%d ms · p99=%d ms · máx=%d ms · %d barrados pelo disjuntor · %d chamadas ao parceiro%n",
                cenario, Percentil.de(duracoes, 50), Percentil.de(duracoes, 99),
                Percentil.de(duracoes, 100), barradosPeloDisjuntor, chamadasRecebidas());

        // Nenhuma exceção subiu: os cem pedidos foram respondidos.
        assertThat(degradados).isEqualTo(PEDIDOS);

        // E respondidos dentro do SLO — inclusive o pior deles, e não só o p99.
        assertThat(Percentil.de(duracoes, 100)).isLessThan(PoliticaDeResiliencia.SLO.toMillis());

        // A maioria esmagadora nem chegou a tentar: o disjuntor abriu nos primeiros pedidos
        // e o resto custou microssegundos.
        assertThat(barradosPeloDisjuntor).isGreaterThan(PEDIDOS * 8 / 10);

        // O parceiro caído recebeu uma dúzia de chamadas, e não trezentas.
        assertThat(chamadasRecebidas()).isLessThanOrEqualTo(20);
    }
}
