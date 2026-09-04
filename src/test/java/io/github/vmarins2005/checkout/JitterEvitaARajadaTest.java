package io.github.vmarins2005.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Por que a espera entre tentativas é sorteada.
 *
 * <p>Quarenta clientes falham no parceiro. Com espera fixa, todos voltam a bater exatamente
 * duzentos milissegundos depois: o retry não espalhou a carga, apenas a adiou. É o mecanismo
 * por trás de metade dos incidentes que "voltaram sozinhos e caíram de novo" — o parceiro
 * levanta, recebe a frota inteira de uma vez, e cai outra vez.
 *
 * <p>A medição é do <b>intervalo entre a primeira e a segunda tentativa de cada cliente</b>,
 * e não do horário absoluto das chamadas. A primeira versão deste teste media horário
 * absoluto e não conseguiu distinguir os dois casos: quarenta clientes não chegam de fato no
 * mesmo milissegundo, e essa dispersão inicial mascarava a que estava sob teste. O intervalo
 * de cada cliente isola a única variável que muda — quanto cada um esperou.
 */
class JitterEvitaARajadaTest extends ParceiroFalso {

    private static final int CLIENTES = 24;

    /** Duas bastam: o que se mede é o intervalo entre a primeira e a segunda. */
    private static final int TENTATIVAS = 2;

    /**
     * 400 ms, e não os 40 ms da política de produção, porque o escalonamento de threads
     * desta máquina espalha as chamadas em algumas dezenas de milissegundos por conta
     * própria. Com uma espera curta, esse ruído tem o mesmo tamanho do efeito sob medição.
     */
    private static final Duration ESPERA = Duration.ofMillis(400);

    /** Dispersão que o ruído da máquina produz sozinho, medida com espera fixa. */
    private static final long RUIDO_TOLERADO = 100;

    @Test
    @DisplayName("espera fixa devolve a frota inteira de uma vez; sorteada espalha")
    void esperaFixaDevolveAFrotaInteiraDeUmaVez() {
        parceiroComErro();

        List<Long> comEsperaFixa = esperasMedidas(IntervalFunction.of(ESPERA));
        parceiro.resetRequests();
        List<Long> comEsperaSorteada = esperasMedidas(IntervalFunction.ofRandomized(ESPERA, 0.5));

        long dispersaoFixa = dispersao(comEsperaFixa);
        long dispersaoSorteada = dispersao(comEsperaSorteada);

        System.out.printf("espera fixa:     p10=%d ms · p90=%d ms · dispersão %d ms%n",
                percentil(comEsperaFixa, 10), percentil(comEsperaFixa, 90), dispersaoFixa);
        System.out.printf("espera sorteada: p10=%d ms · p90=%d ms · dispersão %d ms%n",
                percentil(comEsperaSorteada, 10), percentil(comEsperaSorteada, 90), dispersaoSorteada);

        assertThat(comEsperaFixa).hasSize(CLIENTES);
        assertThat(comEsperaSorteada).hasSize(CLIENTES);

        // Com espera fixa, todo mundo esperou o mesmo tanto: a rajada volta inteira, e o que
        // sobra de dispersão é ruído da máquina, não política.
        assertThat(dispersaoFixa).isLessThan(RUIDO_TOLERADO);

        // Com espera sorteada, a mesma carga chega repartida no tempo: cada cliente espera
        // um tempo sorteado em [200, 600] ms.
        assertThat(dispersaoSorteada).isGreaterThan(200);
        assertThat(dispersaoSorteada).isGreaterThan(3 * dispersaoFixa);
    }

    /** Quanto cada cliente esperou entre a primeira e a segunda tentativa. */
    private List<Long> esperasMedidas(IntervalFunction espera) {
        disparaAFrota(checkoutCom(espera));

        return parceiro.getAllServeEvents().stream()
                .collect(Collectors.groupingBy(
                        evento -> evento.getRequest().getHeader("Idempotency-Key"),
                        Collectors.mapping(
                                evento -> evento.getRequest().getLoggedDate().getTime(),
                                Collectors.toList())))
                .values().stream()
                .filter(instantes -> instantes.size() >= 2)
                .map(instantes -> {
                    var ordenados = instantes.stream().sorted(Comparator.naturalOrder()).toList();
                    return ordenados.get(1) - ordenados.get(0);
                })
                .toList();
    }

    private void disparaAFrota(Autorizador checkout) {
        var largada = new CountDownLatch(1);
        try (var frota = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLIENTES; i++) {
                int cliente = i;
                frota.submit(() -> {
                    largada.await();
                    return checkout.autorizar(Cobranca.de("c-" + cliente, 5_000));
                });
            }
            largada.countDown();
        }
    }

    /**
     * Distância entre o p10 e o p90 das esperas.
     *
     * <p>A primeira versão contava quantos clientes caíam na mesma janela de 50 ms, e o teste
     * balançava entre 30 e 39 conforme a carga da máquina: um punhado de clientes escorrega
     * dezenas de milissegundos por escalonamento de thread, e isso desmontava a contagem sem
     * mudar nada sobre a espera configurada. Descartando as duas pontas, o que sobra é a
     * dispersão que a política de espera realmente produz.
     */
    private long dispersao(List<Long> esperas) {
        return percentil(esperas, 90) - percentil(esperas, 10);
    }

    private long percentil(List<Long> esperas, double percentil) {
        return Percentil.de(esperas.stream().mapToLong(Long::longValue).toArray(), percentil);
    }

    /** Disjuntor fora do caminho: se ele abrisse, as retentativas nem sairiam. */
    private Autorizador checkoutCom(IntervalFunction espera) {
        var disjuntor = CircuitBreaker.of("parceiro", CircuitBreakerConfig.custom()
                .slidingWindowSize(1_000)
                .minimumNumberOfCalls(1_000)
                .build());
        var retentativa = Retry.of("parceiro", RetryConfig.custom()
                .maxAttempts(TENTATIVAS)
                .intervalFunction(espera)
                .retryExceptions(ParceiroIndisponivelException.class)
                .build());
        return new Autorizador(PoliticaDeResiliencia.proteger(
                ParceiroHttp.com(base(), Duration.ofMillis(300)), disjuntor, retentativa));
    }
}
