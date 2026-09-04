package io.github.vmarins2005.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * O que o disjuntor economiza, e para quem.
 *
 * <p>Para nós: cada chamada a um parceiro morto custa o timeout inteiro, e é uma thread
 * parada durante todo esse tempo. Para o parceiro: ele está tentando levantar e continua
 * recebendo a carga que o derrubou.
 *
 * <p>Estados escritos em milissegundos aqui; em produção, os valores do
 * {@link PoliticaDeResiliencia#disjuntorPadrao()}. A forma é a mesma.
 */
class ODisjuntorParaDeBaterEmPortaFechadaTest extends ParceiroFalso {

    private static final Duration TIMEOUT = Duration.ofMillis(150);
    private static final Duration ESPERA_ABERTO = Duration.ofMillis(300);
    private static final int JANELA = 10;
    private static final int SONDAGENS = 3;

    @Test
    @DisplayName("dez timeouts abrem o disjuntor, e as chamadas seguintes custam quase nada")
    void dezTimeoutsAbremODisjuntor() {
        parceiroLento(1_000, 10);
        var disjuntor = novoDisjuntor();
        var checkout = checkoutCom(disjuntor);

        long comParceiroMorto = Cronometro.milissegundos(() -> pedidos(checkout, "antes", JANELA));
        assertThat(disjuntor.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        var depois = Cronometro.mede(() -> pedidos(checkout, "depois", JANELA));

        System.out.printf("%d chamadas com o disjuntor fechado: %d ms · com ele aberto: %d ms%n",
                JANELA, comParceiroMorto, depois.milissegundos());

        // Cada chamada antes custou o timeout inteiro. Depois, nem sai da máquina.
        assertThat(comParceiroMorto).isGreaterThan(JANELA * TIMEOUT.toMillis() * 8 / 10);
        assertThat(depois.milissegundos()).isLessThan(50);
        assertThat(depois.valor()).allMatch(decisao -> decisao instanceof Decisao.RevisaoManual);

        // E o parceiro parou de receber carga: as dez últimas nem chegaram nele.
        assertThat(chamadasRecebidas()).isEqualTo(JANELA);
    }

    @Test
    @DisplayName("depois da espera, ele testa o terreno com poucas chamadas e fecha")
    void depoisDaEsperaEleTestaOTerrenoEFecha() throws InterruptedException {
        parceiroLento(1_000, 10);
        var disjuntor = novoDisjuntor();
        var checkout = checkoutCom(disjuntor);
        pedidos(checkout, "queda", JANELA);
        assertThat(disjuntor.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        Thread.sleep(ESPERA_ABERTO.toMillis() + 50);
        parceiroSaudavel(10);

        // A primeira chamada depois da espera é o que move o disjuntor para meio-aberto:
        // sem tráfego, ele não descobre sozinho que o parceiro voltou.
        var decisoes = pedidos(checkout, "sondagem", SONDAGENS);

        assertThat(decisoes).allMatch(decisao -> decisao.equals(new Decisao.Aprovada(10)));
        assertThat(disjuntor.getState()).isEqualTo(CircuitBreaker.State.CLOSED);
    }

    @Test
    @DisplayName("se o parceiro ainda está mal, as sondagens reabrem o disjuntor")
    void seOParceiroAindaEstaMalAsSondagensReabrem() throws InterruptedException {
        parceiroLento(1_000, 10);
        var disjuntor = novoDisjuntor();
        var checkout = checkoutCom(disjuntor);
        pedidos(checkout, "queda", JANELA);

        Thread.sleep(ESPERA_ABERTO.toMillis() + 50);
        pedidos(checkout, "sondagem", SONDAGENS);

        assertThat(disjuntor.getState()).isEqualTo(CircuitBreaker.State.OPEN);

        // Só três chamadas foram gastas para descobrir isso — e não uma janela inteira.
        // É esse o preço do meio-aberto: sondar custa, e custa pouco.
        assertThat(chamadasRecebidas()).isEqualTo(JANELA + SONDAGENS);
    }

    private java.util.List<Decisao> pedidos(Autorizador checkout, String prefixo, int quantidade) {
        var decisoes = new java.util.ArrayList<Decisao>(quantidade);
        for (int i = 0; i < quantidade; i++) {
            decisoes.add(checkout.autorizar(Cobranca.de(prefixo + "-" + i, 5_000)));
        }
        return decisoes;
    }

    private CircuitBreaker novoDisjuntor() {
        return CircuitBreaker.of("parceiro", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(JANELA)
                .minimumNumberOfCalls(JANELA)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(ESPERA_ABERTO)
                .permittedNumberOfCallsInHalfOpenState(SONDAGENS)
                .recordExceptions(ParceiroIndisponivelException.class)
                .build());
    }

    /** Sem retry: aqui só o disjuntor está sob medição. */
    private Autorizador checkoutCom(CircuitBreaker disjuntor) {
        var semRetentativa = Retry.of("parceiro", RetryConfig.custom().maxAttempts(1).build());
        return new Autorizador(PoliticaDeResiliencia.proteger(
                ParceiroHttp.com(base(), TIMEOUT), disjuntor, semRetentativa));
    }
}
