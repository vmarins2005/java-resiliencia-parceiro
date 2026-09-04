# ADR 0003 — A resposta degradada escolhida para cada falha

Status: aceito · 2026-09-04 · supera: —

## Contexto

Timeout, retry, disjuntor e compartimento decidem **quando desistir**. Nenhum deles decide o
que responder depois de desistir — e essa é a única parte que o usuário vê.

Uma política de resiliência que termina em exceção subindo até o controlador entrega um erro
500 mais rápido. É melhor que travar, e não é resiliência.

## A pergunta certa

Não é "como não falhar". É **"o que este sistema consegue afirmar sem o parceiro?"**.

O parceiro dá um score de risco. Sem ele, o checkout não sabe se a cobrança é fraude. As
opções eram três:

**1. Aprovar tudo.**
Escolhe o prejuízo financeiro no lugar da indisponibilidade — e essa escolha é do negócio,
não da engenharia. Numa queda longa, é exatamente o momento em que fraudador ativo percebe e
explora. Vale para um carrinho de R$ 30, não para um de R$ 30.000.

**2. Recusar tudo.**
Parece "seguro" e é a pior das três: o parceiro cair vira faturamento zero, e clientes
legítimos levam um "não" que ninguém consegue explicar.

**3. Mandar para revisão manual. — escolhida**

## Decisão

Sem parecer do parceiro, a cobrança vai para a **fila de revisão manual**.

`Decisao.RevisaoManual` é um caso do `sealed interface`, ao lado de `Aprovada` e `Recusada`.
Isso é deliberado: "o parceiro caiu" é um **caminho previsto do domínio**, e não uma exceção
escapando. Quem escreve um `switch` sobre `Decisao` é obrigado pelo compilador a decidir o
que fazer com ele.

A degradação é a mesma para toda falha de indisponibilidade — timeout, 5xx, conexão
derrubada, disjuntor aberto, compartimento cheio — porque para o domínio elas são a mesma
coisa: não temos parecer. O que muda é o `motivo`, que vai para o log e para o painel.

## O compartimento é parte da resposta degradada

Um parceiro que só fica **lento** não gera erro nenhum — gera espera, e espera come threads.
Quando as threads acabam, o que cai não é a funcionalidade que depende do parceiro: é o
processo inteiro.

`IsolacaoDeRecursosTest` mede, com o parceiro respondendo em 400 ms e sem um único erro:

| pool | tempo até pedidos que **não usam o parceiro** rodarem |
| --- | --- |
| compartilhado | **840 ms** |
| separado | 7 ms |

E com um compartimento de 4 vagas para 16 clientes simultâneos: 4 atendidos, **12 recusados
na hora**, e o parceiro que estava mal recebeu 4 chamadas em vez de 16.

`maxWaitDuration` é zero de propósito: fila de espera é a mesma espera com outro nome. Um
"não" imediato é uma resposta; uma vaga na fila é uma thread parada.

> Recusar 12 de 16 na hora é melhor do que atender 16 em quatro segundos, porque o segundo
> caso não atende 16 — ele derruba o processo antes disso.

## Consequências

- \+ Com o parceiro 100% fora do ar, os cem pedidos são respondidos dentro do SLO. É o
  critério de conclusão do projeto, e ele está medido em três formas de queda.
- \+ Nenhuma exceção de biblioteca vaza para o domínio: `CallNotPermittedException` e
  `BulkheadFullException` viram `ParceiroIndisponivelException` na fronteira, em
  `PoliticaDeResiliencia`.
- − **A fila de revisão manual tem gente e tem capacidade.** Numa queda de duas horas, ela
  recebe tudo, e "degradar" vira "empurrar o problema para uma equipe que não cabe nele".
  Isso não está resolvido aqui e não se resolve em código: precisa de limite na fila e de
  uma segunda regra para quando ela encher — provavelmente aprovar automaticamente abaixo de
  um valor.
- − A decisão de negócio ("revisão manual, não aprovação automática") está embutida no
  `Autorizador`. Num sistema real ela seria configurável por faixa de valor, e o número
  `LIMITE_DE_RISCO = 30` também.
- − O compartimento recusa pelo simples fato de haver concorrência, sem olhar valor. O
  cliente de R$ 30.000 leva o mesmo "não" que o de R$ 30.
