package io.github.vmarins2005.checkout;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.okJson;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.serverError;
import static com.github.tomakehurst.wiremock.client.WireMock.urlEqualTo;
import static org.assertj.core.api.Assertions.assertThat;

import com.github.tomakehurst.wiremock.stubbing.Scenario;
import io.github.resilience4j.circuitbreaker.CircuitBreaker;
import io.github.resilience4j.circuitbreaker.CircuitBreakerConfig;
import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.Retry;
import io.github.resilience4j.retry.RetryConfig;
import java.time.Duration;
import java.util.Set;
import java.util.stream.Collectors;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Retry é a proteção mais fácil de ligar e a mais fácil de errar.
 *
 * <p>Ele multiplica a carga sobre um parceiro que já está mal, e repete efeitos colaterais
 * que quem escreveu o código não sabia que existiam. O que decide se ele é seguro não é a
 * configuração: é a <b>operação</b> ser idempotente.
 */
class RetrySoOndeEIdempotenteTest extends ParceiroFalso {

    private static final Duration TIMEOUT = Duration.ofMillis(300);
    private static final int TENTATIVAS = 3;

    @Test
    @DisplayName("duas falhas transitórias e a terceira tentativa salva o pedido")
    void duasFalhasTransitoriasEATerceiraTentativaSalva() {
        parceiroQueFalhaDuasVezesEDepoisAcerta();

        var decisao = checkoutCom(ParceiroHttp.com(base(), TIMEOUT)).autorizar(Cobranca.de("c-1", 5_000));

        assertThat(decisao).isEqualTo(new Decisao.Aprovada(10));
        assertThat(chamadasRecebidas()).isEqualTo(3);
    }

    @Test
    @DisplayName("cada tentativa é uma execução no parceiro — inclusive as que falharam")
    void cadaTentativaEUmaExecucaoNoParceiro() {
        parceiroComErro();

        checkoutCom(ParceiroHttp.com(base(), TIMEOUT)).autorizar(Cobranca.de("c-2", 5_000));

        // O parceiro respondeu 500 três vezes. Isso não quer dizer que ele não fez nada três
        // vezes: um erro depois de processar é indistinguível, do lado de cá, de um erro
        // antes de processar. Numa operação com efeito colateral, são três efeitos.
        assertThat(chamadasRecebidas()).isEqualTo(TENTATIVAS);
    }

    @Test
    @DisplayName("a chave de idempotência é a mesma nas três tentativas")
    void aChaveDeIdempotenciaEAMesmaNasTresTentativas() {
        parceiroComErro();

        checkoutCom(ParceiroHttp.com(base(), TIMEOUT)).autorizar(Cobranca.de("c-3", 5_000));

        // É isto que torna o retry seguro: as três tentativas são reconhecíveis pelo parceiro
        // como o mesmo pedido. A chave é o id da cobrança, que existe antes da primeira
        // tentativa e não muda.
        assertThat(chavesEnviadas()).containsExactly("c-3");
    }

    @Test
    @DisplayName("chave gerada por tentativa não deduplica nada — e parece que sim")
    void chaveGeradaPorTentativaNaoDeduplicaNada() {
        parceiroComErro();

        checkoutCom(ParceiroHttp.comChavePorTentativa(base(), TIMEOUT)).autorizar(Cobranca.de("c-4", 5_000));

        // Três chaves distintas: para o parceiro, três pedidos diferentes. O cabeçalho está
        // lá, o código passa em revisão, e a proteção não existe.
        assertThat(chavesEnviadas()).hasSize(TENTATIVAS);
    }

    @Test
    @DisplayName("4xx não é retentado: o parceiro já disse que o pedido está errado")
    void quatrocentoENaoERetentado() {
        parceiro.stubFor(post("/analises")
                .willReturn(aResponse().withStatus(422).withBody("cpf inválido")));

        var decisao = checkoutCom(ParceiroHttp.com(base(), TIMEOUT)).autorizar(Cobranca.de("c-5", 5_000));

        assertThat(decisao).isInstanceOf(Decisao.RevisaoManual.class);
        // Uma chamada, não três. Retentar um pedido malformado só multiplica o mesmo erro —
        // e, numa incidência em massa, vira ataque de negação de serviço contra o parceiro.
        assertThat(chamadasRecebidas()).isEqualTo(1);
    }

    private void parceiroQueFalhaDuasVezesEDepoisAcerta() {
        parceiro.stubFor(post("/analises").inScenario("instável")
                .whenScenarioStateIs(Scenario.STARTED).willSetStateTo("segunda")
                .willReturn(serverError()));
        parceiro.stubFor(post("/analises").inScenario("instável")
                .whenScenarioStateIs("segunda").willSetStateTo("terceira")
                .willReturn(serverError()));
        parceiro.stubFor(post("/analises").inScenario("instável")
                .whenScenarioStateIs("terceira")
                .willReturn(okJson("{\"score\":10}")));
    }

    private Set<String> chavesEnviadas() {
        return parceiro.findAll(postRequestedFor(urlEqualTo("/analises"))).stream()
                .map(pedido -> pedido.getHeader("Idempotency-Key"))
                .collect(Collectors.toSet());
    }

    /** Disjuntor deliberadamente fora do caminho: aqui só o retry está sob medição. */
    private Autorizador checkoutCom(ParceiroAntifraude parceiroHttp) {
        var disjuntor = CircuitBreaker.of("parceiro", CircuitBreakerConfig.custom()
                .slidingWindowSize(1_000)
                .minimumNumberOfCalls(1_000)
                .build());
        var retentativa = Retry.of("parceiro", RetryConfig.custom()
                .maxAttempts(TENTATIVAS)
                .intervalFunction(IntervalFunction.of(Duration.ofMillis(10)))
                .retryExceptions(ParceiroIndisponivelException.class)
                .ignoreExceptions(CobrancaRejeitadaException.class)
                .build());
        return new Autorizador(PoliticaDeResiliencia.proteger(parceiroHttp, disjuntor, retentativa));
    }
}
