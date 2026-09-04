package io.github.vmarins2005.checkout;

import static org.assertj.core.api.Assertions.assertThat;

import io.github.resilience4j.bulkhead.Bulkhead;
import io.github.resilience4j.bulkhead.BulkheadConfig;
import java.time.Duration;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * A falha que não precisa de erro nenhum.
 *
 * <p>Um parceiro que responde 500 é fácil: dá para contar, abrir disjuntor, degradar. Um
 * parceiro que só fica <b>lento</b> é pior — ele não gera falha, gera espera, e espera come
 * threads. Quando as threads acabam, o que cai não é a funcionalidade que depende do
 * parceiro: é o processo inteiro, incluindo tudo o que nem chega perto dele.
 */
class IsolacaoDeRecursosTest extends ParceiroFalso {

    private static final int TAMANHO_DO_POOL = 8;
    private static final int PEDIDOS_LENTOS = 16;
    private static final int PEDIDOS_RAPIDOS = 4;
    private static final int ATRASO_DO_PARCEIRO = 400;

    private static final int VAGAS_NO_COMPARTIMENTO = 4;
    private static final int CLIENTES_SIMULTANEOS = 16;

    @Test
    @DisplayName("um parceiro lento derruba até o que não depende dele")
    void umParceiroLentoDerrubaAteOQueNaoDependeDele() throws InterruptedException {
        parceiroLento(ATRASO_DO_PARCEIRO, 10);

        long comPoolCompartilhado = tempoAteOsPedidosRapidosRodarem(true);
        long comPoolSeparado = tempoAteOsPedidosRapidosRodarem(false);

        System.out.printf("pedidos que não usam o parceiro — pool compartilhado: %d ms · pool separado: %d ms%n",
                comPoolCompartilhado, comPoolSeparado);

        // Nenhum erro em lugar nenhum. Só espera — e ela é contagiosa quando o recurso
        // esperado é compartilhado.
        assertThat(comPoolCompartilhado).isGreaterThan(500);
        assertThat(comPoolSeparado).isLessThan(100);
    }

    @Test
    @DisplayName("o compartimento devolve um não rápido em vez de uma espera longa")
    void oCompartimentoDevolveUmNaoRapido() throws InterruptedException {
        parceiroLento(ATRASO_DO_PARCEIRO, 10);

        var compartimento = Bulkhead.of("parceiro", BulkheadConfig.custom()
                .maxConcurrentCalls(VAGAS_NO_COMPARTIMENTO)
                // Zero de propósito: fila de espera é a mesma espera, só com outro nome.
                .maxWaitDuration(Duration.ZERO)
                .build());
        var checkout = new Autorizador(PoliticaDeResiliencia.protegerComCompartimento(
                ParceiroHttp.com(base(), Duration.ofSeconds(5)), compartimento));

        var recusados = new AtomicInteger();
        var atendidos = new AtomicInteger();
        var largada = new CountDownLatch(1);

        try (var frota = Executors.newVirtualThreadPerTaskExecutor()) {
            for (int i = 0; i < CLIENTES_SIMULTANEOS; i++) {
                int cliente = i;
                frota.submit(() -> {
                    largada.await();
                    var decisao = checkout.autorizar(Cobranca.de("c-" + cliente, 5_000));
                    if (decisao instanceof Decisao.RevisaoManual) {
                        recusados.incrementAndGet();
                    } else {
                        atendidos.incrementAndGet();
                    }
                    return null;
                });
            }
            largada.countDown();
        }

        System.out.printf("%d clientes, %d vagas: %d atendidos, %d recusados na hora%n",
                CLIENTES_SIMULTANEOS, VAGAS_NO_COMPARTIMENTO, atendidos.get(), recusados.get());

        assertThat(atendidos.get()).isEqualTo(VAGAS_NO_COMPARTIMENTO);
        assertThat(recusados.get()).isEqualTo(CLIENTES_SIMULTANEOS - VAGAS_NO_COMPARTIMENTO);

        // O compartimento também protege o outro lado: o parceiro que está mal recebeu
        // quatro chamadas, e não dezesseis.
        assertThat(chamadasRecebidas()).isEqualTo(VAGAS_NO_COMPARTIMENTO);
    }

    /**
     * Enfileira dezesseis chamadas ao parceiro lento e mede quanto tempo pedidos que
     * <b>não usam o parceiro</b> levam para começar a rodar.
     */
    private long tempoAteOsPedidosRapidosRodarem(boolean poolCompartilhado) throws InterruptedException {
        ExecutorService poolDoParceiro = Executors.newFixedThreadPool(TAMANHO_DO_POOL);
        ExecutorService poolDoResto = poolCompartilhado ? poolDoParceiro : Executors.newFixedThreadPool(2);
        var cliente = ParceiroHttp.com(base(), Duration.ofSeconds(5));

        try {
            for (int i = 0; i < PEDIDOS_LENTOS; i++) {
                int pedido = i;
                poolDoParceiro.submit(() -> cliente.analisar(Cobranca.de("lento-" + pedido, 5_000)));
            }

            var rapidosConcluidos = new CountDownLatch(PEDIDOS_RAPIDOS);
            long inicio = System.nanoTime();
            for (int i = 0; i < PEDIDOS_RAPIDOS; i++) {
                poolDoResto.submit(rapidosConcluidos::countDown);
            }
            rapidosConcluidos.await();
            return (System.nanoTime() - inicio) / 1_000_000;
        } finally {
            // Esperar o encerramento não é zelo: sem isso, as chamadas ainda em voo chegam
            // no parceiro depois do teste seguinte já ter limpado o diário, e aparecem lá
            // como chamadas que ele não fez. Foi assim que este teste sujou o do
            // compartimento na primeira execução.
            poolDoParceiro.shutdownNow();
            poolDoResto.shutdownNow();
            poolDoParceiro.awaitTermination(2, TimeUnit.SECONDS);
            poolDoResto.awaitTermination(2, TimeUnit.SECONDS);
        }
    }
}
