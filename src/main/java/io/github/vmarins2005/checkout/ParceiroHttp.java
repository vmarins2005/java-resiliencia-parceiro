package io.github.vmarins2005.checkout;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.net.http.HttpTimeoutException;
import java.time.Duration;
import java.util.UUID;

/**
 * Cliente HTTP do parceiro.
 *
 * <p>O timeout é do <b>cliente HTTP</b>, e não um {@code TimeLimiter} por volta da chamada.
 * A diferença é grande e está no ADR 0001: o timeout do cliente aborta a leitura do socket
 * sem custar uma thread extra; um limitador genérico precisa de outra thread para
 * cronometrar e, quando ela desiste, a chamada original continua ocupando a primeira.
 *
 * <p>{@code timeoutDeResposta} é anulável de propósito: o teste que mede o que acontece
 * <i>sem</i> timeout precisa de um cliente sem timeout, e essa versão precisa ser possível
 * de escrever para ser possível de medir.
 */
public final class ParceiroHttp implements ParceiroAntifraude {

    private static final ObjectMapper JSON = new ObjectMapper();

    private final HttpClient http;
    private final URI enderecoDeAnalises;
    private final Duration timeoutDeResposta;
    private final boolean chaveEstavel;

    private ParceiroHttp(URI base, Duration timeoutDeConexao, Duration timeoutDeResposta, boolean chaveEstavel) {
        var cliente = HttpClient.newBuilder();
        if (timeoutDeConexao != null) {
            cliente.connectTimeout(timeoutDeConexao);
        }
        this.http = cliente.build();
        this.enderecoDeAnalises = base.resolve("/analises");
        this.timeoutDeResposta = timeoutDeResposta;
        this.chaveEstavel = chaveEstavel;
    }

    /** Cliente com os dois timeouts que todo cliente HTTP deveria ter. */
    public static ParceiroHttp com(String base, Duration timeoutDeResposta) {
        return new ParceiroHttp(URI.create(base), Duration.ofMillis(200), timeoutDeResposta, true);
    }

    /** O cliente que o {@code HttpClient.newHttpClient()} devolve: espera para sempre. */
    public static ParceiroHttp semTimeout(String base) {
        return new ParceiroHttp(URI.create(base), null, null, true);
    }

    /**
     * Versão errada de propósito: gera uma chave de idempotência nova a cada tentativa.
     *
     * <p>Parece idempotente — tem o cabeçalho, passa em revisão de código — e não é. Cada
     * retentativa vira um pedido novo aos olhos do parceiro. Existe aqui para que o teste
     * possa <b>medir</b> a diferença em vez de afirmá-la.
     */
    public static ParceiroHttp comChavePorTentativa(String base, Duration timeoutDeResposta) {
        return new ParceiroHttp(URI.create(base), Duration.ofMillis(200), timeoutDeResposta, false);
    }

    @Override
    public Parecer analisar(Cobranca cobranca) {
        var pedido = HttpRequest.newBuilder(enderecoDeAnalises)
                .header("Content-Type", "application/json")
                // A chave é o id da cobrança, estável entre tentativas — ver ADR 0002.
                .header("Idempotency-Key", chaveEstavel ? cobranca.id() : UUID.randomUUID().toString())
                .POST(HttpRequest.BodyPublishers.ofString(corpo(cobranca)));
        if (timeoutDeResposta != null) {
            pedido.timeout(timeoutDeResposta);
        }

        HttpResponse<String> resposta;
        try {
            resposta = http.send(pedido.build(), HttpResponse.BodyHandlers.ofString());
        } catch (HttpTimeoutException e) {
            throw new ParceiroIndisponivelException(
                    "timeout de " + timeoutDeResposta.toMillis() + " ms", e);
        } catch (IOException e) {
            throw new ParceiroIndisponivelException("falha de rede: " + e.getMessage(), e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ParceiroIndisponivelException("chamada interrompida", e);
        }

        // 5xx é problema do parceiro e passa; 4xx é problema nosso e retentar não conserta.
        if (resposta.statusCode() >= 500) {
            throw new ParceiroIndisponivelException("parceiro respondeu HTTP " + resposta.statusCode());
        }
        if (resposta.statusCode() >= 400) {
            throw new CobrancaRejeitadaException(resposta.statusCode(), resposta.body());
        }
        return leParecer(resposta.body());
    }

    private static String corpo(Cobranca cobranca) {
        return "{\"id\":\"%s\",\"valor\":%d,\"cpf\":\"%s\"}"
                .formatted(cobranca.id(), cobranca.valorEmCentavos(), cobranca.cpf());
    }

    private static Parecer leParecer(String corpo) {
        try {
            return new Parecer(JSON.readTree(corpo).path("score").asInt());
        } catch (IOException e) {
            // Resposta ilegível é indisponibilidade do parceiro, não erro nosso.
            throw new ParceiroIndisponivelException("resposta ilegível do parceiro: " + corpo, e);
        }
    }
}
