package io.github.vmarins2005.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.http.Fault;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;

/**
 * Um parceiro de verdade, que fala HTTP de verdade, e que quebra do jeito que parceiros
 * quebram.
 *
 * <p>Um dublê em memória não serviria: as falhas que este projeto estuda — timeout de socket,
 * conexão derrubada no meio, atraso — só existem quando há um socket. Um {@code mock} que
 * lança exceção instantaneamente prova que o {@code catch} funciona, e não prova nada sobre
 * uma thread presa esperando resposta.
 */
abstract class ParceiroFalso {

    /** Folgado de propósito: vários testes disparam dezenas de chamadas ao mesmo tempo. */
    private static final int THREADS_DO_SERVIDOR = 80;

    protected static WireMockServer parceiro;

    @BeforeAll
    static void sobeOParceiro() {
        parceiro = new WireMockServer(options().dynamicPort().containerThreads(THREADS_DO_SERVIDOR));
        parceiro.start();
    }

    @AfterAll
    static void desceOParceiro() {
        parceiro.stop();
    }

    @BeforeEach
    void limpaOParceiro() {
        parceiro.resetAll();
    }

    protected static String base() {
        return "http://localhost:" + parceiro.port();
    }

    protected static void parceiroSaudavel(int score) {
        parceiro.stubFor(post("/analises").willReturn(respostaCom(score)));
    }

    protected static void parceiroLento(int atrasoEmMilissegundos, int score) {
        parceiro.stubFor(post("/analises")
                .willReturn(respostaCom(score).withFixedDelay(atrasoEmMilissegundos)));
    }

    /** O parceiro está de pé, responde rápido, e responde erro. */
    protected static void parceiroComErro() {
        parceiro.stubFor(post("/analises").willReturn(serverError()));
    }

    /** O parceiro derruba a conexão no meio — falha de rede, não resposta HTTP. */
    protected static void parceiroDerrubandoAConexao() {
        parceiro.stubFor(post("/analises")
                .willReturn(com.github.tomakehurst.wiremock.client.WireMock.aResponse()
                        .withFault(Fault.CONNECTION_RESET_BY_PEER)));
    }

    protected static long chamadasRecebidas() {
        return parceiro.findAll(
                        com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor(urlEqualTo("/analises")))
                .size();
    }

    private static com.github.tomakehurst.wiremock.client.ResponseDefinitionBuilder respostaCom(int score) {
        return okJson("{\"score\":" + score + "}");
    }
}
