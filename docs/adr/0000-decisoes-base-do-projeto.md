# ADR 0000 — Decisões base do projeto

Status: aceito · 2026-09-04 · supera: —

## Contexto

O projeto existe para transformar "resiliência" de uma lista de anotações numa **conta**: o
que cada proteção custa, o que ela economiza, e o que acontece quando ela não está lá.

## Decisões

### 1. Um parceiro HTTP de verdade, e não um dublê em memória

As falhas que este projeto estuda só existem quando há um socket: timeout de leitura, conexão
derrubada no meio, parceiro que aceita a conexão e fica mudo. Um `mock` que lança exceção
instantaneamente prova que o `catch` funciona e **não prova nada** sobre uma thread presa
esperando resposta — que é o problema.

WireMock é uma biblioteca Java, então não precisa de Docker, e o parceiro é um servidor HTTP
completo.

### 2. Cada proteção é medida sem ela e com ela

Não há teste neste repositório que apenas verifique que a configuração foi aplicada. Todos
comparam dois números: quanto custa a chamada sem a proteção, quanto custa com ela.

É o que separa "ligamos o disjuntor" de "o disjuntor economiza 1,5 segundo a cada dez
chamadas a um parceiro morto".

### 3. A composição é escrita à mão, e não por anotação

O starter do Spring monta retry, disjuntor e compartimento por anotação, numa ordem que está
documentada e que quase ninguém lê. A ordem muda o comportamento de forma medível — o ADR
0004 mostra 4 pedidos contra 10, e 10 chamadas ao parceiro contra 30.

Escrevendo a composição, a decisão fica no código, no ponto onde ela foi tomada. Em um
sistema real, a anotação é preferível pela ergonomia; aqui o objetivo é o oposto do
implícito.

### 4. O timeout é do cliente HTTP, e não um `TimeLimiter`

Um limitador genérico precisa de outra thread para cronometrar, e quando ela desiste a
chamada original continua ocupando a primeira. O timeout do `HttpClient` aborta a leitura do
socket: uma thread, e ela é realmente liberada. Ver ADR 0001.

### 5. O histórico de latência é sintético, com semente fixa

Log-normal com mediana de 20 ms — a forma que latência de serviço tem de verdade: maioria
rápida, cauda longa. Semente fixa para que o número no README seja o mesmo em qualquer
máquina.

É simulação, e está dito onde ela é usada. O que ela sustenta não depende dos valores exatos:
depende de a cauda ser vários múltiplos da mediana, o que é verdade em qualquer serviço real.

### 6. O caso de uso nunca lança

`Autorizador.autorizar` devolve `Decisao` em todos os caminhos. Uma exceção subindo dali
seria o checkout inteiro fora do ar junto com o parceiro — exatamente o que o projeto existe
para evitar.

## Consequências

- A suíte roda em ~31 segundos, sem Docker, e produz os números que estão no README.
- Há código de produção escrito errado de propósito (`ParceiroHttp.semTimeout`,
  `comChavePorTentativa`, `protegerComRetryPorDentro`). Eles existem para que a comparação
  seja medida em vez de afirmada, e cada um está marcado no Javadoc.
- Vários testes dependem de tempo. Isso é inerente ao assunto: não existe medir timeout sem
  medir tempo. As margens são folgadas e os motivos estão comentados onde apertam.
