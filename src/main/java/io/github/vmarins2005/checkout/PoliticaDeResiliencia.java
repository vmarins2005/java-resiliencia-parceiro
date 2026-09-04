package io.github.vmarins2005.checkout;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadFullException;
import io.github.resilience4j.circuitbreaker.CallNotPermittedException;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Compõe as proteções em volta da chamada ao parceiro.
 *
 * <p>A composição é escrita à mão, e não montada por anotação, porque a <b>ordem</b> dos
 * decoradores muda o comportamento de forma medível — e neste projeto ela precisa estar
 * visível no ponto onde a decisão foi tomada. Ver ADR 0004.
 */
public final class PoliticaDeResiliencia {

    /** O compromisso com quem chama o checkout. Tudo abaixo é derivado dele. */
    public static final Duration SLO = Duration.ofMillis(400);

    /** p99 do parceiro medido em 1000 chamadas saudáveis, com folga. Ver ADR 0001. */
    public static final Duration TIMEOUT_POR_TENTATIVA = Duration.ofMillis(120);

    /** Espera entre tentativas, sorteada em ±50% para não sincronizar a frota. */
    public static final Duration ESPERA_ENTRE_TENTATIVAS = Duration.ofMillis(40);

    /**
     * Duas tentativas, e não três, porque 2 × 120 ms + 60 ms de espera é o que cabe no SLO
     * de 400 ms com folga. O número de tentativas é <b>consequência</b> do orçamento de
     * tempo, não uma escolha independente — ver ADR 0001.
     */
    public static final int TENTATIVAS = 2;

    private PoliticaDeResiliencia() {}

    /** Pior caso previsto de uma chamada protegida. Tem que caber no {@link #SLO}. */
    public static Duration piorCasoPrevisto() {
        return TIMEOUT_POR_TENTATIVA.multipliedBy(TENTATIVAS)
                .plus(ESPERA_ENTRE_TENTATIVAS.multipliedBy(TENTATIVAS - 1).multipliedBy(3).dividedBy(2));
    }

    /** Disjuntor de produção: 10 chamadas de janela, abre com 50% de falha, espera 5 s. */
    public static CircuitBreakerConfig disjuntorPadrao() {
        return CircuitBreakerConfig.custom()
                .slidingWindowType(CircuitBreakerConfig.SlidingWindowType.COUNT_BASED)
                .slidingWindowSize(10)
                .minimumNumberOfCalls(10)
                .failureRateThreshold(50f)
                .waitDurationInOpenState(Duration.ofSeconds(5))
                .permittedNumberOfCallsInHalfOpenState(3)
                // Só indisponibilidade abre o disjuntor. Uma enxurrada de cobranças
                // malformadas é defeito nosso e não deve derrubar o parceiro para todos.
                .recordExceptions(ParceiroIndisponivelException.class)
                .ignoreExceptions(CobrancaRejeitadaException.class)
                .build();
    }

    /** Duas tentativas, espera sorteada em ±50% — ver ADR 0002. */
    public static RetryConfig retentativaPadrao() {
        return RetryConfig.custom()
                .maxAttempts(TENTATIVAS)
                // Sorteada, e não fixa: espera fixa sincroniza a frota inteira na mesma
                // milésima de segundo e transforma a recuperação do parceiro numa rajada.
                .intervalFunction(IntervalFunction.ofRandomized(ESPERA_ENTRE_TENTATIVAS, 0.5))
                .retryExceptions(ParceiroIndisponivelException.class)
                .ignoreExceptions(CobrancaRejeitadaException.class)
                .build();
    }

    /**
     * Retry <b>por fora</b> do disjuntor: cada tentativa é uma chamada que o disjuntor vê.
     *
     * <p>É a ordem padrão do starter do Spring, e a que este projeto usa. O ADR 0004 mostra
     * o que muda quando se inverte.
     */
    public static Function<Cobranca, Parecer> proteger(
            ParceiroAntifraude parceiro, CircuitBreaker disjuntor, Retry retentativa) {

        return cobranca -> {
            Supplier<Parecer> chamada = () -> parceiro.analisar(cobranca);
            Supplier<Parecer> comDisjuntor = CircuitBreaker.decorateSupplier(disjuntor, chamada);
            Supplier<Parecer> comRetentativa = Retry.decorateSupplier(retentativa, comDisjuntor);
            return traduzindoRecusaDoDisjuntor(comRetentativa);
        };
    }

    /** Retry <b>por dentro</b>: o disjuntor vê uma chamada por pedido, não uma por tentativa. */
    public static Function<Cobranca, Parecer> protegerComRetryPorDentro(
            ParceiroAntifraude parceiro, CircuitBreaker disjuntor, Retry retentativa) {

        return cobranca -> {
            Supplier<Parecer> chamada = () -> parceiro.analisar(cobranca);
            Supplier<Parecer> comRetentativa = Retry.decorateSupplier(retentativa, chamada);
            Supplier<Parecer> comDisjuntor = CircuitBreaker.decorateSupplier(disjuntor, comRetentativa);
            return traduzindoRecusaDoDisjuntor(comDisjuntor);
        };
    }

    /**
     * Limita quantas chamadas simultâneas ao parceiro podem existir.
     *
     * <p>Sem isso, um parceiro lento não precisa dar erro nenhum para derrubar o sistema:
     * basta demorar, e todas as threads ficam esperando por ele — inclusive as que iam
     * atender pedidos que nem tocam no parceiro. Ver ADR 0003.
     */
    public static Function<Cobranca, Parecer> protegerComCompartimento(
            ParceiroAntifraude parceiro, Bulkhead compartimento) {

        return cobranca -> {
            Supplier<Parecer> chamada = () -> parceiro.analisar(cobranca);
            try {
                return Bulkhead.decorateSupplier(compartimento, chamada).get();
            } catch (BulkheadFullException e) {
                throw new ParceiroIndisponivelException(
                        "compartimento cheio: já há " + compartimento.getBulkheadConfig().getMaxConcurrentCalls()
                                + " chamadas em andamento", e);
            }
        };
    }

    /**
     * O disjuntor aberto lança uma exceção da biblioteca. Ela vira indisponibilidade do
     * parceiro aqui, na fronteira, para que o resto do sistema não precise conhecer o
     * Resilience4j — nem o {@link Autorizador}, nem quem lê o log.
     */
    private static Parecer traduzindoRecusaDoDisjuntor(Supplier<Parecer> decorado) {
        try {
            return decorado.get();
        } catch (CallNotPermittedException e) {
            throw new ParceiroIndisponivelException("disjuntor aberto: nem tentamos chamar o parceiro", e);
        }
    }
}
