package io.github.vmarins2005.checkout;

import java.util.function.Supplier;

/** Mede em milissegundos, com relógio monotônico — {@code currentTimeMillis} não serve. */
final class Cronometro {

    record Medido<T>(T valor, long milissegundos) {}

    private Cronometro() {}

    static long milissegundos(Runnable acao) {
        return mede(() -> {
            acao.run();
            return null;
        }).milissegundos();
    }

    static <T> Medido<T> mede(Supplier<T> acao) {
        long inicio = System.nanoTime();
        T valor = acao.get();
        return new Medido<>(valor, (System.nanoTime() - inicio) / 1_000_000);
    }
}
