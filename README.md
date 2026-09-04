# Resiliência medida — o que cada proteção custa e o que ela economiza

Projeto de estudo de **Resilience4j contra um parceiro que falha de verdade**: um serviço de
checkout que precisa de um parecer antifraude antes de autorizar uma cobrança, e um parceiro
(WireMock) que responde erro, fica mudo, derruba a conexão e demora.

Décimo segundo de uma série em que cada repositório isola um conceito.

Não há aqui nenhum teste que apenas verifique que a configuração foi aplicada. Todos comparam
dois números: quanto custa **sem** a proteção, quanto custa **com** ela.

## Como rodar

Não precisa de Docker — WireMock é uma biblioteca Java, e sobe um servidor HTTP completo.

```bash
./mvnw test
```

22 testes, ~31 segundos.

## O critério de conclusão

> Com o parceiro 100% fora do ar, a aplicação continua respondendo dentro do SLO — com
> resposta degradada e sem thread presa.

Cem pedidos, três formas de queda, SLO de 400 ms:

| parceiro | p50 | p99 | máximo | barrados pelo disjuntor | chamadas que ele levou |
| --- | --- | --- | --- | --- | --- |
| respondendo 500 | 0 ms | 61 ms | 69 ms | 95 de 100 | 10 |
| mudo (nunca responde) | 0 ms | 289 ms | **296 ms** | 95 de 100 | 10 |
| derrubando a conexão | 0 ms | 70 ms | 103 ms | 95 de 100 | 10 |

Cem respostas, zero exceções, o pior caso dentro do SLO — e o parceiro caído recebeu **10**
chamadas, não 200.

## Os números de cada proteção

### Timeout

O parceiro demora 1.500 ms:

| cliente | tempo até responder |
| --- | --- |
| `HttpClient.newHttpClient()` | **1.506 ms** |
| com timeout de 120 ms | 124 ms |

Sem timeout, nenhuma outra proteção funciona: disjuntor precisa de falhas para contar, e uma
chamada que nunca termina nunca vira falha.

### De onde sai o 120

Do p99 do parceiro, medido em 1000 chamadas saudáveis — p50 21 ms, p95 62 ms, **p99 94 ms**,
máximo 151 ms — cruzado com o SLO:

```
pior caso = 2 tentativas × 120 ms + 60 ms de espera = 300 ms  <  SLO de 400 ms
```

O número de tentativas é **consequência** do orçamento de tempo, não uma escolha
independente. Três tentativas de 120 ms não cabem em 400 ms.

E o custo de escolher errado, medido:

| corte | chamadas saudáveis mortas (em 1000) |
| --- | --- |
| p50 (21 ms) | **494** |
| p95 (62 ms) | 50 |
| p99 (94 ms) | 9 |
| **120 ms (escolhido)** | **4** |
| 2 × p99 (188 ms) | 0 |

Um timeout curto demais *fabrica* a indisponibilidade que deveria conter — e com retry ligado
ainda dobra a carga sobre o parceiro no momento em que decidiu que ele está com problema.

### Disjuntor

Parceiro morto, timeout de 150 ms:

| dez chamadas | tempo |
| --- | --- |
| disjuntor fechado | **1.507 ms** |
| disjuntor aberto | **0 ms** |

E o parceiro para de receber carga: as dez seguintes nem saem da máquina.

### Ordem dos decoradores

Mesma configuração, ordens diferentes:

| ordem | pedidos até abrir | chamadas que o parceiro levou |
| --- | --- | --- |
| retry **por fora** do disjuntor | **4** | **10** |
| retry por dentro | 10 | **30** |

Com o retry por dentro, um pedido que falhou três vezes chega ao disjuntor como *uma* falha:
a informação é jogada fora exatamente onde seria útil. Ver [ADR 0004](docs/adr/0004-retry-por-fora-do-disjuntor.md).

### Jitter

Vinte e quatro clientes falham juntos. Medindo o intervalo entre a primeira e a segunda
tentativa **de cada cliente**, com espera base de 400 ms:

| espera | p10 | p90 | dispersão |
| --- | --- | --- | --- |
| fixa | 413 ms | 454 ms | **41 ms** |
| sorteada em ±50% | 256 ms | 565 ms | **309 ms** |

Mesma carga total. Com espera fixa, o retry não espalhou nada — apenas adiou a rajada para o
instante em que o parceiro está tentando levantar. Os 41 ms que sobram não são política: são
o escalonamento de threads da máquina, e a primeira versão deste teste não conseguia
distinguir uma coisa da outra.

### Compartimento

Parceiro respondendo em 400 ms, **sem um único erro**:

| pool | tempo até pedidos que *não usam o parceiro* rodarem |
| --- | --- |
| compartilhado | **840 ms** |
| separado | 7 ms |

Um parceiro que só fica lento não gera falha, gera espera — e espera come threads. O que cai
não é a funcionalidade que depende dele: é o processo inteiro.

Com 4 vagas para 16 clientes simultâneos: 4 atendidos, **12 recusados na hora**, e o parceiro
recebeu 4 chamadas em vez de 16.

## A armadilha do retry

Três tentativas são **três execuções no parceiro**, inclusive as que "falharam": um erro
depois de processar é indistinguível, do lado de cá, de um erro antes de processar.

O que torna o retry seguro é uma chave de idempotência que existe antes da primeira tentativa
e não muda entre elas. E há uma versão errada que passa em revisão de código:

```java
// parece idempotente, tem o cabeçalho, e não deduplica nada
.header("Idempotency-Key", UUID.randomUUID().toString())
```

`chaveGeradaPorTentativaNaoDeduplicaNada` mede: três chaves distintas, três pedidos
diferentes aos olhos do parceiro.

`Cobranca` recusa id vazio no construtor por causa disso — uma cobrança sem id não pode ser
reenviada com segurança, e a hora de descobrir isso é na construção, não no meio de um
incidente.

## A resposta degradada

`Decisao` é um `sealed interface` com três casos: `Aprovada`, `Recusada` e `RevisaoManual`.

O terceiro está ali de propósito: "o parceiro caiu" é um **caminho previsto do domínio**, e
não uma exceção escapando. Quem escreve um `switch` sobre `Decisao` é obrigado pelo
compilador a decidir o que fazer com ele.

Aprovar tudo troca indisponibilidade por prejuízo — e é decisão do negócio, não da
engenharia. Recusar tudo transforma a queda do parceiro em faturamento zero. Ver
[ADR 0003](docs/adr/0003-resposta-degradada-escolhida-para-cada-falha.md), que também
registra o problema **não resolvido**: a fila de revisão manual tem gente e tem capacidade.

## Decisões registradas

| ADR | Assunto |
| --- | --- |
| [0000](docs/adr/0000-decisoes-base-do-projeto.md) | Escopo, parceiro real, medição sem e com cada proteção |
| [0001](docs/adr/0001-valores-de-timeout-derivados-do-p99-do-parceiro.md) | Timeout derivado do p99 e do SLO — e por que não `TimeLimiter` |
| [0002](docs/adr/0002-onde-o-retry-e-seguro-porque-a-operacao-e-idempotente.md) | Onde o retry é seguro, e a chave que passa em revisão sem funcionar |
| [0003](docs/adr/0003-resposta-degradada-escolhida-para-cada-falha.md) | A resposta degradada, e o compartimento como parte dela |
| [0004](docs/adr/0004-retry-por-fora-do-disjuntor.md) | Ordem dos decoradores |

## Código errado de propósito

Três, todos marcados no Javadoc, todos existindo para que a comparação seja **medida** em vez
de afirmada:

- `ParceiroHttp.semTimeout` — o cliente que o `HttpClient.newHttpClient()` devolve;
- `ParceiroHttp.comChavePorTentativa` — a idempotência que não deduplica;
- `PoliticaDeResiliencia.protegerComRetryPorDentro` — a ordem que triplica a carga.

## Exercícios

1. **Aumente `TENTATIVAS` para 3** e rode `ComOParceiroForaDoArOCheckoutRespondeTest`. O
   cenário "mudo" estoura o SLO. Agora encontre o valor de `TIMEOUT_POR_TENTATIVA` que faz
   três tentativas caberem, e veja quantas chamadas saudáveis ele passa a matar.
2. **Troque o timeout do `HttpClient` por um `TimeLimiter`** e meça quantas threads ficam
   ocupadas com o parceiro mudo. É o argumento do ADR 0001, e ele merece ser visto.
3. **Inverta a ordem dos decoradores** no critério de conclusão e conte as chamadas que o
   parceiro caído passa a receber.
4. **Ligue `automaticTransitionFromOpenToHalfOpenEnabled`** e explique por que ela custa uma
   thread agendadora — e em que situação essa thread paga por si.
5. **Faça o parceiro voltar no meio da rodada** de 100 pedidos e observe quanto tempo o
   sistema leva para perceber. É o custo do `waitDurationInOpenState`, e ninguém pensa nele
   até a primeira vez que o parceiro volta e o checkout continua degradado.

## Regras de trabalho neste repositório

- Nenhuma chamada de rede sem timeout de conexão e de resposta.
- Timeout sai do p99 do parceiro **e** do SLO de quem chama; o número de tentativas é
  consequência dos dois.
- Retry só sobre falha transitória, e só com chave de idempotência estável.
- Espera entre tentativas sempre sorteada.
- Toda dependência externa tem resposta degradada explícita no tipo de retorno.

## O que eu faria diferente

_A preencher depois de usar._
