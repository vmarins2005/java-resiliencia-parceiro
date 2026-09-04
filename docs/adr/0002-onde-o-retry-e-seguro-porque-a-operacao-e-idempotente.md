# ADR 0002 — Onde o retry é seguro, porque a operação é idempotente

Status: aceito · 2026-09-04 · supera: —

## Contexto

Retry é a proteção mais fácil de ligar e a mais fácil de errar. Ele multiplica a carga sobre
um parceiro que já está mal, e repete efeitos colaterais que quem escreveu o código não sabia
que existiam.

O que decide se ele é seguro **não é a configuração**. É a operação ser idempotente.

## O que o teste mede

`cadaTentativaEUmaExecucaoNoParceiro`: com o parceiro respondendo 500, três tentativas
resultam em **três chamadas recebidas por ele**.

Isso importa porque, do lado de cá, um erro *depois* de processar é indistinguível de um erro
*antes* de processar. O parceiro pode ter analisado, cobrado e registrado a cobrança, e
tropeçado só na hora de serializar a resposta. Nós vemos "500" nos dois casos.

> Retentar não é "tentar de novo". É "fazer de novo", e torcer para que o outro lado saiba
> que é a mesma coisa.

## O que torna seguro

Uma chave de idempotência que **existe antes da primeira tentativa e não muda entre elas**.

Aqui é o `id` da cobrança, gerado por nós. `Cobranca` recusa id vazio no construtor
justamente por isso: uma cobrança sem id não pode ser reenviada com segurança, e a hora de
descobrir isso é na construção, não no meio de um incidente.

`aChaveDeIdempotenciaEAMesmaNasTresTentativas` verifica que as três chamadas carregam
`c-3`.

## A armadilha que passa em revisão de código

`ParceiroHttp.comChavePorTentativa` existe para ser medido: ele gera um `UUID` novo a cada
chamada.

O cabeçalho `Idempotency-Key` está lá. O código passa em revisão. E
`chaveGeradaPorTentativaNaoDeduplicaNada` mede **três chaves distintas** — para o parceiro,
são três pedidos diferentes, e a proteção não existe.

A chave precisa identificar o *pedido*, não a *tentativa*. É uma distinção de uma linha de
código e de zero linhas de diferença visual.

## O que não se retenta

**4xx.** O parceiro entendeu o pedido e disse que ele está errado; repetir só repete o erro.
`quatrocentoENaoERetentado` mede **uma** chamada, não três.

Isso está no `RetryConfig` (`retryExceptions` só `ParceiroIndisponivelException`,
`ignoreExceptions` `CobrancaRejeitadaException`), mas a decisão de verdade está no
`ParceiroHttp`, que separa 5xx de 4xx em duas exceções distintas. Sem essa separação na
fronteira, nenhuma configuração de retry consegue distinguir as duas.

Numa incidência em massa — um deploy nosso que passou a mandar CPF malformado — retentar 4xx
transforma o nosso defeito num ataque de negação de serviço contra o parceiro.

## Decisão

- Retry **apenas** sobre falha transitória: timeout, erro de rede, 5xx.
- Chave de idempotência estável, derivada do identificador do pedido.
- Duas tentativas, pelo orçamento do SLO (ADR 0001).
- Espera entre tentativas **sorteada**, nunca fixa (ver abaixo).

## Por que a espera é sorteada

Com espera fixa, os clientes que falharam juntos voltam a bater **exatamente** ao mesmo
tempo. O retry não espalhou a carga: adiou. E é durante a recuperação do parceiro que essa
rajada chega.

`JitterEvitaARajadaTest` mede o intervalo entre a primeira e a segunda tentativa de cada um
dos 24 clientes, com espera base de 400 ms:

| espera | p10 | p90 | dispersão |
| --- | --- | --- | --- |
| fixa | 413 ms | 454 ms | **41 ms** |
| sorteada em ±50% | 256 ms | 565 ms | **309 ms** |

Mesma carga total nos dois casos. O que muda é a distribuição no tempo — e é ela que decide
se o parceiro consegue levantar.

Os 41 ms de dispersão com espera fixa não são política: são o escalonamento de threads da
máquina. A primeira versão deste teste contava clientes por janela de 50 ms e balançava entre
30 e 39 conforme a carga, porque esse ruído tem o mesmo tamanho de uma janela. Descartar as
duas pontas com p10 e p90 foi o que separou o efeito do ruído.

## Consequências

- \+ Falha transitória some sem chegar ao usuário, e o teste
  `duasFalhasTransitoriasEATerceiraTentativaSalva` mostra isso acontecendo.
- \+ A carga extra sobre o parceiro é limitada (2×, não 3×) e espalhada no tempo.
- − Retry **sempre** multiplica a carga sobre quem já está mal. O disjuntor é o que impede
  essa multiplicação de virar avalanche — e a ordem entre os dois importa (ADR 0004).
- − A idempotência depende do parceiro **honrar** a chave. Isso é contrato, não código nosso,
  e é a primeira coisa a confirmar antes de ligar retry numa operação com efeito colateral.
  Nada aqui verifica que ele honra; o teste verifica que nós enviamos.
- − Espera sorteada torna a latência do pior caso menos previsível. Por isso o orçamento do
  ADR 0001 usa o **teto** do sorteio, e não a média.
