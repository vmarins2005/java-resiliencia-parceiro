# ADR 0001 — Valores de timeout derivados do p99 do parceiro

Status: aceito · 2026-09-04 · supera: —

## Contexto

"Bota 30 segundos" e "bota 100 milissegundos" são o mesmo erro: um número escolhido sem olhar
para o parceiro. O primeiro não protege de nada — a thread fica presa meio minuto e o cliente
já desistiu há muito. O segundo derruba chamadas que teriam dado certo.

O histórico do parceiro, em 1000 chamadas **saudáveis**:

| percentil | latência |
| --- | --- |
| p50 | 21 ms |
| p95 | 62 ms |
| p99 | 94 ms |
| máximo | 151 ms |

A cauda é 4,5 vezes a mediana. É por isso que dimensionar timeout pela média é errado: a
média fica colada no p50 e ignora a cauda inteira, que é justamente o que o timeout corta.

## Quanto custa cada corte

Contando quantas das 1000 chamadas saudáveis cada corte mataria:

| corte | chamadas mortas | o que isso significa |
| --- | --- | --- |
| p50 (21 ms) | **494** | metade do tráfego vira erro com o parceiro 100% saudável |
| p95 (62 ms) | 50 | 5% de erro inventado por nós |
| p99 (94 ms) | 9 | 0,9% |
| 2 × p99 (188 ms) | **0** | nenhuma |

E não é só simulação: `timeoutCurtoDemaisCriaAIndisponibilidade` roda 100 chamadas contra o
parceiro com um timeout no p50 e mede entre 55 e 61 falhas — um pouco acima das 46 previstas
pelo histórico, porque o tempo de rede e de serialização também conta.

> Um timeout curto demais **fabrica** a indisponibilidade que ele deveria conter. E, com
> retry ligado, ele ainda dobra a carga sobre o parceiro no momento exato em que decidiu que
> o parceiro está com problema.

## A conta é de trás para frente

O timeout não sai só do parceiro: sai do **compromisso com quem chama**.

```
SLO = 400 ms
pior caso = tentativas × timeout + esperas entre elas
          = 2 × 120 ms + 60 ms = 300 ms  ✓ cabe
```

Com um timeout de 188 ms (o dobro do p99, que não mata ninguém), duas tentativas custariam
436 ms e estourariam o SLO. Não dá para ter os dois.

## Decisão

**Timeout de 120 ms por tentativa, duas tentativas, dentro de um SLO de 400 ms.**

120 ms é 1,28 × o p99. Sacrifica **4 chamadas em 1000** (0,4%) — e sacrifica de propósito:
é o preço de caber no orçamento de tempo.

O número de tentativas é **consequência** do orçamento, e não uma escolha independente. Foi
por isso que ele desceu de três para duas: três tentativas de 120 ms não cabem em 400 ms.

E o que a primeira tentativa sacrifica, a segunda recupera: para o pedido falhar de verdade,
as duas precisam cair na cauda — 0,0016% dos casos, medido em
`oValorEscolhidoSacrificaAPontaDaCauda`.

## Por que o timeout é do cliente HTTP, e não um `TimeLimiter`

Um `TimeLimiter` do Resilience4j envolve a chamada num `CompletableFuture` e precisa de
**outra thread** para cronometrar. Quando ele desiste, ele devolve o controle a quem chamou —
mas a chamada original continua ocupando a thread onde estava, presa no `read` do socket, até
o parceiro responder ou o sistema operacional desistir.

Ou seja: com um parceiro mudo, o `TimeLimiter` protege o tempo de resposta e **não protege as
threads** — que é metade do motivo de existir um timeout.

O timeout do `HttpClient` aborta a leitura do socket: uma thread só, e ela é de fato
devolvida. `OTimeoutEAPrimeiraProtecaoTest` mede: 1.500 ms sem timeout, menos de 600 ms com
timeout de 120 ms.

## Consequências

- \+ O número tem origem: sai de uma medição, e refazer a medição é o procedimento para mudá-lo.
- \+ O SLO e o timeout são a mesma conta, e o teste do critério de conclusão verifica que
  fecha: `piorCasoPrevisto() < SLO`.
- − 0,4% das chamadas saudáveis são cortadas. É consciente, está medido, e o retry cobre
  quase tudo.
- − O p99 do parceiro **muda**. Um timeout derivado de um p99 de seis meses atrás é um
  timeout arbitrário com uma boa história. Isso exige remedir, e nada neste repositório
  lembra de fazer isso — num sistema real, o valor deveria vir de configuração e ser
  revisado com o painel de latência do parceiro à vista.
- − Timeout de conexão e timeout de resposta são coisas diferentes e os dois precisam
  existir. O de conexão (200 ms aqui) é o que salva do parceiro que nem completa o aperto de
  mãos — e é o único que age quando o endereço simplesmente engole os pacotes sem responder.
