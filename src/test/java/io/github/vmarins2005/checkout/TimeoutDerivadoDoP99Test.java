package io.github.vmarins2005.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import java.time.Duration;
import java.util.Random;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * De onde sai o número do timeout.
 *
 * <p>"Bota 30 segundos" e "bota 100 milissegundos" são erros do mesmo tipo: um número
 * escolhido sem olhar para o parceiro. O primeiro não protege de nada; o segundo derruba
 * chamadas que teriam dado certo — e transforma um parceiro saudável numa fonte de erro que
 * nós mesmos criamos.
 *
 * <p>O histórico de latência é sintético, gerado com semente fixa, mas com a forma que
 * latência de rede tem de verdade: log-normal, mediana baixa e cauda longa. É a cauda que
 * decide o timeout.
 */
class TimeoutDerivadoDoP99Test extends ParceiroFalso {

    private static final int HISTORICO = 1_000;
    private static final int TRAFEGO_MEDIDO = 100;

    @Test
    @DisplayName("o histórico do parceiro diz quanto cada corte de timeout custa")
    void oHistoricoDizQuantoCadaCorteCusta() {
        long[] historico = latenciasSaudaveis(HISTORICO);
        long p50 = Percentil.de(historico, 50);
        long p95 = Percentil.de(historico, 95);
        long p99 = Percentil.de(historico, 99);
        long maximo = Percentil.de(historico, 100);

        System.out.printf("histórico: p50=%d ms · p95=%d ms · p99=%d ms · máx=%d ms%n",
                p50, p95, p99, maximo);
        System.out.printf("corte no p50 mata %d de %d chamadas saudáveis%n",
                Percentil.acimaDe(historico, p50), HISTORICO);
        System.out.printf("corte no p95 mata %d · no p99 mata %d · no dobro do p99 mata %d%n",
                Percentil.acimaDe(historico, p95),
                Percentil.acimaDe(historico, p99),
                Percentil.acimaDe(historico, p99 * 2));

        // A cauda é longa: o p99 é vários múltiplos da mediana. É por isso que dimensionar
        // timeout pela média é errado — a média fica perto do p50 e ignora a cauda inteira.
        assertThat(p99).isGreaterThan(p50 * 3);

        assertThat(Percentil.acimaDe(historico, p50)).isBetween(450L, 550L);
        assertThat(Percentil.acimaDe(historico, p95)).isBetween(1L, 60L);
        assertThat(Percentil.acimaDe(historico, p99)).isLessThanOrEqualTo(10L);
        assertThat(Percentil.acimaDe(historico, p99 * 2)).isZero();
    }

    @Test
    @DisplayName("timeout curto demais cria a indisponibilidade que ele deveria evitar")
    void timeoutCurtoDemaisCriaAIndisponibilidade() {
        long[] historico = latenciasSaudaveis(HISTORICO);
        long[] trafego = primeiras(historico, TRAFEGO_MEDIDO);
        long corte = Percentil.de(historico, 50);

        long previstas = Percentil.acimaDe(trafego, corte);
        long medidas = falhasCom(Duration.ofMillis(corte), trafego);

        System.out.printf("timeout no p50 (%d ms): previstas %d falhas em %d, medidas %d%n",
                corte, previstas, TRAFEGO_MEDIDO, medidas);

        // O parceiro está 100% saudável. Todas essas falhas são nossas.
        assertThat(medidas).isCloseTo(previstas, org.assertj.core.data.Offset.offset(20L));
        assertThat(medidas).isGreaterThan(TRAFEGO_MEDIDO / 3);
    }

    @Test
    @DisplayName("timeout com folga sobre o p99 não derruba nenhuma chamada saudável")
    void timeoutComFolgaSobreOP99NaoDerrubaNada() {
        long[] historico = latenciasSaudaveis(HISTORICO);
        long[] trafego = primeiras(historico, TRAFEGO_MEDIDO);
        long corte = Percentil.de(historico, 99) * 2;

        long medidas = falhasCom(Duration.ofMillis(corte), trafego);

        System.out.printf("timeout no dobro do p99 (%d ms): %d falhas em %d%n",
                corte, medidas, TRAFEGO_MEDIDO);

        assertThat(medidas).isZero();
        // E o valor escolhido para produção cabe no orçamento do SLO, que é o outro lado da
        // conta: não adianta o timeout ser generoso se o SLO não paga por ele.
        assertThat(PoliticaDeResiliencia.TIMEOUT_POR_TENTATIVA.toMillis())
                .isGreaterThanOrEqualTo(Percentil.de(historico, 99));
        assertThat(PoliticaDeResiliencia.piorCasoPrevisto()).isLessThan(PoliticaDeResiliencia.SLO);
    }

    @Test
    @DisplayName("o valor escolhido sacrifica a ponta da cauda, e o retry a recupera")
    void oValorEscolhidoSacrificaAPontaDaCauda() {
        long[] historico = latenciasSaudaveis(HISTORICO);
        long corte = PoliticaDeResiliencia.TIMEOUT_POR_TENTATIVA.toMillis();

        long sacrificadas = Percentil.acimaDe(historico, corte);
        System.out.printf("timeout de produção (%d ms): sacrifica %d de %d chamadas saudáveis (%.1f%%)%n",
                corte, sacrificadas, HISTORICO, 100.0 * sacrificadas / HISTORICO);

        // Não é zero, e não pode ser: o SLO de 400 ms com duas tentativas não paga por um
        // timeout de 188 ms. A escolha é consciente — abre-se mão da ponta da cauda para
        // caber no orçamento de tempo.
        assertThat(sacrificadas).isPositive();
        assertThat(sacrificadas).isLessThan(HISTORICO / 100);

        // E o que se sacrifica na primeira tentativa, a segunda recupera: para o pedido
        // falhar de verdade, as duas tentativas precisam cair na cauda.
        double probabilidadeDeUma = (double) sacrificadas / HISTORICO;
        System.out.printf("as duas tentativas caírem na cauda: %.4f%%%n", 100 * probabilidadeDeUma * probabilidadeDeUma);
        assertThat(probabilidadeDeUma * probabilidadeDeUma).isLessThan(0.0001);
    }

    /** Dispara uma chamada para cada atraso do histórico e conta quantas o timeout matou. */
    private long falhasCom(Duration timeout, long[] atrasos) {
        encadeiaAtrasos(atrasos);
        var cliente = ParceiroHttp.com(base(), timeout);

        long falhas = 0;
        for (int i = 0; i < atrasos.length; i++) {
            try {
                cliente.analisar(Cobranca.de("c-" + i, 5_000));
            } catch (ParceiroIndisponivelException e) {
                falhas++;
            }
        }
        return falhas;
    }

    /**
     * Uma cadeia de estados no WireMock faz o parceiro devolver os atrasos <b>nesta ordem</b>.
     * Sorteio dentro do dublê tornaria a medição irrepetível.
     */
    private void encadeiaAtrasos(long[] atrasos) {
        for (int i = 0; i < atrasos.length; i++) {
            parceiro.stubFor(post("/analises")
                    .inScenario("latências do parceiro")
                    .whenScenarioStateIs(i == 0 ? Scenario.STARTED : "chamada-" + i)
                    .willSetStateTo("chamada-" + (i + 1))
                    .willReturn(okJson("{\"score\":10}").withFixedDelay((int) atrasos[i])));
        }
    }

    /**
     * Log-normal com mediana de 20 ms e sigma 0,7 — a forma que latência de serviço tem: a
     * maioria das chamadas rápida, e uma cauda que vai longe. Semente fixa para que o número
     * no README seja o mesmo em qualquer máquina.
     */
    private static long[] latenciasSaudaveis(int quantidade) {
        var sorteio = new Random(42);
        long[] amostras = new long[quantidade];
        for (int i = 0; i < quantidade; i++) {
            double milissegundos = Math.exp(Math.log(20) + sorteio.nextGaussian() * 0.7);
            amostras[i] = Math.max(5, Math.round(milissegundos));
        }
        return amostras;
    }

    private static long[] primeiras(long[] amostras, int quantidade) {
        long[] recorte = new long[quantidade];
        System.arraycopy(amostras, 0, recorte, 0, quantidade);
        return recorte;
    }
}
