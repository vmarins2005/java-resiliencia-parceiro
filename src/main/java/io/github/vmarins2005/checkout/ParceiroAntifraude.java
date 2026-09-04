package io.github.vmarins2005.checkout;

/** O parceiro externo que dá o parecer de risco. */
public interface ParceiroAntifraude {

    /**
     * @throws ParceiroIndisponivelException falha transitória — vale retentar
     * @throws CobrancaRejeitadaException a cobrança está errada — retentar só repete o erro
     */
    Parecer analisar(Cobranca cobranca);
}
