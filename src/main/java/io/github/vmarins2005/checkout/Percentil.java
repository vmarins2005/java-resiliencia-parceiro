package io.github.vmarins2005.checkout;

import java.util.Arrays;

/**
 * Percentil pelo método do índice arredondado para cima — o mais comum em ferramenta de
 * observabilidade, e o que importa aqui: p99 é "o valor abaixo do qual estão 99% das
 * amostras", e não a média de nada.
 */
public final class Percentil {

    private Percentil() {}

    public static long de(long[] amostras, double percentil) {
        if (amostras.length == 0) {
            throw new IllegalArgumentException("não existe percentil de amostra vazia");
        }
        long[] ordenadas = amostras.clone();
        Arrays.sort(ordenadas);
        int indice = (int) Math.ceil(percentil / 100.0 * ordenadas.length) - 1;
        return ordenadas[Math.max(0, Math.min(indice, ordenadas.length - 1))];
    }

    /** Quantas amostras ficariam acima de um corte — ou seja, quantas o timeout mataria. */
    public static long acimaDe(long[] amostras, long corte) {
        return Arrays.stream(amostras).filter(valor -> valor > corte).count();
    }
}
