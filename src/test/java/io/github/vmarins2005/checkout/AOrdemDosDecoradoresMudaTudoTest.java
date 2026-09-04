package io.github.vmarins2005.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Function;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retry e disjuntor com a mesma configuração, em ordens diferentes, dão sistemas diferentes.
 *
 * <p>É a decisão que passa despercebida porque, com anotação, ela não aparece em lugar nenhum
 * do código: fica num padrão da biblioteca que quase ninguém lê.
 */
class AOrdemDosDecoradoresMudaTudoTest extends ParceiroFalso {

    private static final int JANELA = 10;
    private static final int TENTATIVAS = 3;
    private static final int LIMITE_DE_PEDIDOS = 50;

    private interface Composicao {
        Function<Cobranca, Parecer> compor(ParceiroAntifraude parceiro, CircuitBreaker disjuntor, Retry retentativa);
    }

    private record Medicao(int pedidosAteAbrir, long chamadasAoParceiro) {}

    @Test
    @DisplayName("retry por fora abre o disjuntor três vezes mais cedo e poupa o parceiro")
    void retryPorForaAbreMaisCedoEPoupaOParceiro() {
        parceiroComErro();

        var porFora = mede(PoliticaDeResiliencia::proteger);
        parceiro.resetRequests();
        var porDentro = mede(PoliticaDeResiliencia::protegerComRetryPorDentro);

        System.out.printf("retry por fora:  abriu em %d pedidos, %d chamadas ao parceiro%n",
                porFora.pedidosAteAbrir(), porFora.chamadasAoParceiro());
        System.out.printf("retry por dentro: abriu em %d pedidos, %d chamadas ao parceiro%n",
                porDentro.pedidosAteAbrir(), porDentro.chamadasAoParceiro());

        // Por fora, cada tentativa é uma chamada que o disjuntor conta: a janela de dez
        // fecha em pouco mais de três pedidos.
        assertThat(porFora.pedidosAteAbrir()).isLessThan(porDentro.pedidosAteAbrir());
        assertThat(porFora.chamadasAoParceiro()).isEqualTo(JANELA);

        // Por dentro, o disjuntor vê um pedido como uma falha só. Ele demora dez pedidos
        // para abrir, e nesse meio-tempo o parceiro levou o triplo da carga.
        assertThat(porDentro.pedidosAteAbrir()).isEqualTo(JANELA);
        assertThat(porDentro.chamadasAoParceiro()).isEqualTo((long) JANELA * TENTATIVAS);
    }

    private Medicao mede(Composicao composicao) {
        var disjuntor = CircuitBreaker.of("parceiro", CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(JANELA)
                .minimumNumberOfCalls(JANELA)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofMinutes(1))
                .recordExceptions(ParceiroIndisponivelException.class)
                .build());
        var retentativa = Retry.of("parceiro", RetryConfig.custom()
                .maxAttempts(TENTATIVAS)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(5)))
                .retryExceptions(ParceiroIndisponivelException.class)
                .build());
        var checkout = new Autorizador(composicao.compor(
                ParceiroHttp.com(base(), Duration.ofMillis(200)), disjuntor, retentativa));

        int pedidos = 0;
        while (disjuntor.getState() != CircuitBreaker.State.OPEN && pedidos < LIMITE_DE_PEDIDOS) {
            checkout.autorizar(Cobranca.de("c-" + pedidos, 5_000));
            pedidos++;
        }
        return new Medicao(pedidos, chamadasRecebidas());
    }
}
