# ADR 0004 — Retry por fora do disjuntor

Status: aceito · 2026-09-04 · supera: —

## Contexto

Retry e disjuntor com **a mesma configuração**, em ordens diferentes, dão sistemas
diferentes.

É a decisão que passa despercebida porque, com anotação, ela não aparece em lugar nenhum do
código: fica num padrão da biblioteca que quase ninguém lê.

```java
// por fora: cada tentativa é uma chamada que o disjuntor conta
Retry.decorateSupplier(retentativa, CircuitBreaker.decorateSupplier(disjuntor, chamada));

// por dentro: o disjuntor conta um pedido como uma falha só
CircuitBreaker.decorateSupplier(disjuntor, Retry.decorateSupplier(retentativa, chamada));
```

## O que muda, medido

Parceiro respondendo 500, janela do disjuntor de 10 chamadas, 3 tentativas por pedido —
`AOrdemDosDecoradoresMudaTudoTest`:

| ordem | pedidos até o disjuntor abrir | chamadas que o parceiro levou |
| --- | --- | --- |
| retry por fora | **4** | **10** |
| retry por dentro | 10 | **30** |

Com o retry por dentro, o disjuntor demora 2,5 vezes mais para perceber, e o parceiro que
está caindo leva **o triplo** da carga nesse meio-tempo.

Faz sentido: por dentro, um pedido que falhou três vezes chega ao disjuntor como *uma* falha.
A informação de que foram três idas ao parceiro é jogada fora exatamente onde ela seria útil.

## A troca

Por fora não é grátis. Quando o disjuntor abre no meio de um pedido, as tentativas restantes
desse pedido são recusadas na hora — o pedido perde o resto do orçamento de retry que teria
com o parceiro apenas instável.

Ou seja:

- **por fora**: o disjuntor reage rápido e protege o parceiro; um pedido azarado perde
  tentativas;
- **por dentro**: cada pedido tem seu orçamento inteiro; o parceiro paga o triplo e continua
  sendo martelado por mais tempo.

Numa falha transitória de uma chamada, a segunda opção atende melhor **aquele** pedido. Numa
queda de verdade, ela atrasa a proteção de todos os outros.

## Decisão

**Retry por fora do disjuntor.**

O caso que importa é a queda, não o soluço: no soluço, uma tentativa a menos vira uma
revisão manual; na queda, três vezes mais carga sobre um parceiro que está tentando levantar
é a diferença entre ele voltar e não voltar.

É também a ordem padrão do starter do Spring (`Retry` por fora de `CircuitBreaker`), o que
significa que a maioria dos sistemas já usa esta — só não sabe.

`CallNotPermittedException` **não está** em `retryExceptions`, então o retry não a retenta: um
disjuntor aberto não vira três recusas instantâneas seguidas.

## Consequências

- \+ O disjuntor abre com a informação completa: ele conta idas ao parceiro, que é o recurso
  que ele protege.
- \+ O parceiro em queda recebe um terço da carga.
- − Pedidos que chegam no instante em que o disjuntor abre perdem tentativas restantes.
- − A composição precisa ser escrita, ou pelo menos conferida. Com anotação ela é invisível,
  e "invisível" aqui quer dizer que ninguém revisou.
- − A tabela acima depende de a janela do disjuntor ser contada em número de chamadas
  (`COUNT_BASED`). Com janela por tempo, os números mudam, e a conclusão — mais informação
  chega ao disjuntor — não.
