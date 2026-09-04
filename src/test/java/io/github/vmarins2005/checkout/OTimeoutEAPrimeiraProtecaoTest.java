package io.github.vmarins2005.checkout;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/**
 * Sem timeout, nenhuma outra proteção funciona.
 *
 * <p>Disjuntor precisa de falhas para contar, e uma chamada que nunca termina nunca vira
 * falha. Retry precisa que a tentativa acabe para começar a próxima. Compartimento não
 * adianta se as vagas nunca são devolvidas. O timeout é o que transforma "lento" em "falhou"
 * — e só depois disso o resto da política tem sobre o que agir.
 */
class OTimeoutEAPrimeiraProtecaoTest extends ParceiroFalso {

    private static final int PARCEIRO_TRAVADO_POR = 1_500;

    @Test
    @DisplayName("sem timeout, a thread espera o quanto o parceiro quiser")
    void semTimeoutAThreadEsperaOQuantoOParceiroQuiser() {
        parceiroLento(PARCEIRO_TRAVADO_POR, 10);
        var cliente = ParceiroHttp.semTimeout(base());

        long duracao = Cronometro.milissegundos(() -> cliente.analisar(Cobranca.de("c-1", 5_000)));

        System.out.printf("parceiro travado por %d ms · cliente sem timeout: %d ms%n",
                PARCEIRO_TRAVADO_POR, duracao);

        // A chamada terminou bem. O problema não é o resultado, é o tempo: quem chamou o
        // checkout desistiu muito antes, e a thread continuou ocupada até o fim.
        assertThat(duracao).isGreaterThan(1_200);
    }

    @Test
    @DisplayName("com timeout, quem decide quanto esperar somos nós")
    void comTimeoutQuemDecideQuantoEsperarSomosNos() {
        parceiroLento(PARCEIRO_TRAVADO_POR, 10);
        var cliente = ParceiroHttp.com(base(), Duration.ofMillis(120));

        long duracao = Cronometro.milissegundos(() ->
                assertThatThrownBy(() -> cliente.analisar(Cobranca.de("c-2", 5_000)))
                        .isInstanceOf(ParceiroIndisponivelException.class)
                        .hasMessageContaining("timeout de 120 ms"));

        System.out.printf("parceiro travado por %d ms · cliente com timeout de 120 ms: %d ms%n",
                PARCEIRO_TRAVADO_POR, duracao);

        assertThat(duracao).isLessThan(600);
    }

    @Test
    @DisplayName("conexão derrubada falha sozinha; é o parceiro mudo que precisa de timeout")
    void conexaoDerrubadaFalhaSozinha() {
        parceiroDerrubandoAConexao();
        var cliente = ParceiroHttp.semTimeout(base());

        // Sem timeout nenhum, e ainda assim rápido: o sistema operacional avisa que a
        // conexão morreu. O caso perigoso é o oposto — o parceiro que aceita a conexão,
        // fica em silêncio e nunca fecha nada. Aí só o timeout salva.
        long duracao = Cronometro.milissegundos(() ->
                assertThatThrownBy(() -> cliente.analisar(Cobranca.de("c-3", 5_000)))
                        .isInstanceOf(ParceiroIndisponivelException.class)
                        .hasMessageContaining("falha de rede"));

        assertThat(duracao).isLessThan(500);
    }
}
