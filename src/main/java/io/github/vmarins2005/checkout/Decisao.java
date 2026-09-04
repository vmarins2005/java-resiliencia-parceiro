package io.github.vmarins2005.checkout;

/**
 * O que o checkout decidiu.
 *
 * <p>{@link RevisaoManual} não é um erro: é a resposta que o sistema dá quando não conseguiu
 * consultar o parceiro. Ela existe no tipo justamente para que "o parceiro caiu" seja um
 * caminho previsto do domínio, e não uma exceção vazando para o usuário — ver ADR 0003.
 */
public sealed interface Decisao {

    record Aprovada(int score) implements Decisao {}

    record Recusada(int score) implements Decisao {}

    record RevisaoManual(String motivo) implements Decisao {}
}
