package io.github.vmarins2005.checkout;

/** Resposta do parceiro de antifraude: quanto maior o score, maior o risco. */
public record Parecer(int score) {

    public Parecer {
        if (score < 0 || score > 100) {
            throw new IllegalArgumentException("score fora da faixa: " + score);
        }
    }
}
