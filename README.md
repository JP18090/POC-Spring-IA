# Projeto Final — API Inteligente de Orçamento com Spring AI + Ollama

> Desafio de Projeto DIO: API de orçamento pessoal com **Spring Boot + Spring AI**, usando **Ollama** (modelos de linguagem **locais e gratuitos**) para entender comandos em linguagem natural e executar **funções reais** da aplicação via **Tool Calling** — tudo testável por uma **interface web simples**.

---

## 1. O que o projeto faz

A API de **budgeting** (orçamento pessoal) registra e consulta transações financeiras de duas formas:

1. **Interface web / REST tradicional** — formulário e filtros que chamam endpoints JSON;
2. **Comando em linguagem natural com IA** — você escreve *"gastei 80 reais no mercado"* e o modelo local (Ollama):
   - entende a intenção;
   - chama a **ferramenta** (`@Tool`) correta — que é um **caso de uso real** da aplicação;
   - grava ou consulta os dados no banco;
   - responde em texto, em português.

**Diferencial desta versão:** nenhuma chave de API paga é necessária — o modelo roda 100% local via Ollama.

---

## 2. Fluxo principal da API

```
┌────────────────────┐  texto   ┌──────────────────────────┐
│ Interface web (HTML│ ───────► │ POST /transactions/ai    │
│ ou cliente REST)   │          └───────────┬──────────────┘
└────────────────────┘                      ▼
                          1. ChatClient + system prompt
                             (modelo local via Ollama)
                                      ▼
                          2. Tool Calling (@Tool)
                             escolhe o caso de uso real:
                             persist-transaction |
                             list-transactions-by-category |
                             list-all-transactions
                                      ▼
                          3. Repositório do domínio
                             JPA ──► MySQL
                                      ▼
┌────────────────────┐  JSON    ┌──────────────────────────┐
│ Resposta em texto  │ ◄─────── │ AiResponse { reply }     │
└────────────────────┘          └──────────────────────────┘
```

**Por que texto e não áudio?** No projeto original (OpenAI), o fluxo era áudio → Whisper → texto → IA → TTS. O Ollama não oferece transcrição nem síntese de voz — então a interface entrega o **texto** (exatamente o que a transcrição geraria) direto para a etapa de IA. O coração do desafio — **Tool Calling conectando IA a funções reais** — é preservado.

---

## 3. Arquitetura (camadas DDD)

```
dio.budgeting
├── domain/                     # negócio puro (sem framework)
│   ├── Transaction.java               # entidade (class: tem identidade)
│   ├── TransactionId.java             # ID fortemente tipado (record + UUID)
│   ├── Category.java                  # enum: GROCERIES, PHARMA, AUTO
│   └── TransactionRepository.java     # contrato de persistência
│
├── application/                # casos de uso = ferramentas da IA
│   ├── PersistTransactionUseCase.java         # @Tool "persist-transaction"
│   ├── ListTransactionsByCategoryUseCase.java # @Tool "list-transactions-by-category"
│   ├── ListAllTransactionsUseCase.java        # @Tool "list-all-transactions" (NOVO)
│   ├── input/PersistTransactionInput.java     # DTO de entrada (@ToolParam)
│   └── output/TransactionOutput.java          # DTO de saída
│
└── infrastructure/             # adapters (HTTP, JPA, IA)
    ├── http/TransactionController.java        # REST + endpoint /ai
    ├── http/request/                          # TransactionRequest, AiRequest
    ├── http/response/                         # TransactionResponse, AiResponse
    └── persistence/                           # TransactionEntity (JPA),
                                               # TransactionEntityRepository (Spring Data),
                                               # JpaTransactionRepository (adapter)
```

**Ponto-chave:** os mesmos casos de uso atendem **o REST e a IA** — a anotação `@Tool` expõe a regra de negócio ao modelo sem duplicar código.

---

## 4. Tecnologias utilizadas

| Tecnologia | Papel |
|---|---|
| Java 25 | Linguagem |
| Spring Boot 4.0.5 | Web, Data JPA, Docker Compose |
| Spring AI 2.0.0-M4 | `ChatClient` + Tool Calling |
| **Ollama + llama3.2** | Modelo de linguagem **local e gratuito** |
| MySQL 9.6 | Persistência (sobe via `compose.yml`) |
| H2 | Banco em memória apenas para os testes |
| HTML + CSS + JS (vanilla) | Interface web de testes (servida em `/`) |
| Lombok / JUnit 5 | Boilerplate / testes |

---

## 5. Como executar a aplicação

**Pré-requisitos:** Java 25 e Docker rodando.

```bash
# 1. Suba a aplicação — o Docker Compose sobe MySQL + Ollama automaticamente
./gradlew bootRun

# 2. Na primeira execução, baixe o modelo (~2 GB, só uma vez):
docker exec -it $(docker ps -qf "ancestor=ollama/ollama") ollama pull llama3.2
```

> **Alternativa sem Docker para a IA:** instale o Ollama na máquina (<https://ollama.com>), rode `ollama pull llama3.2` e depois `ollama serve`. O `compose.yml` continua cuidando do MySQL.

**Acesse a interface web:** <http://localhost:8080>

Configurações da IA em `src/main/resources/application.properties`:

```properties
spring.ai.ollama.base-url=http://localhost:11434
spring.ai.ollama.chat.options.model=llama3.2
spring.ai.ollama.chat.options.temperature=0
spring.ai.ollama.init.pull-model-strategy=WHEN_MISSING   # tenta baixar o modelo sozinho
```

> Dica: modelos menores/maiores podem ser trocados em `chat.options.model` (ex.: `llama3.1`, `qwen2.5`). Prefira modelos com suporte a *tools*.

---

## 6. Como testar o fluxo principal

### Pela interface web (recomendado)

1. Abra <http://localhost:8080>;
2. **Comando com IA:** digite `gastei 80 reais no mercado` e envie — a IA registra a transação de verdade;
3. **Consultar:** clique em *Listar todas* e confirme que o registro aparece (prova de que o Tool Calling executou o caso de uso real);
4. Teste também: `liste meus gastos de farmácia`, `quais foram todas as minhas transações?`;
5. Use o formulário REST para comparar o cadastro manual com o cadastro via IA.

### Pelo terminal (curl)

```bash
# Comando com IA
curl -X POST http://localhost:8080/transactions/ai \
  -H "Content-Type: application/json" \
  -d '{"message":"paguei 45 reais de remédio na farmácia"}'

# Criar transação via REST (valor em centavos)
curl -X POST http://localhost:8080/transactions \
  -H "Content-Type: application/json" \
  -d '{"description":"Mercado","category":"GROCERIES","amount":8000}'

# Consultas
curl http://localhost:8080/transactions            # todas (NOVO)
curl http://localhost:8080/transactions/PHARMA     # por categoria
```

### Testes automatizados

```bash
./gradlew test   # usa H2 em memória; não precisa de Docker nem de Ollama
```

---

## 7. Melhorias implementadas nesta versão

1. **Migração OpenAI → Ollama** — IA 100% local e gratuita, sem chave de API nem custo por chamada. Arquivos: [build.gradle](build.gradle), [application.properties](src/main/resources/application.properties);
2. **Interface web em HTML/CSS/JS** para testar todas as funcionalidades: [index.html](src/main/resources/static/index.html);
3. **Nova ferramenta e endpoint "listar todas as transações"** — [ListAllTransactionsUseCase.java](src/main/java/dio/budgeting/application/ListAllTransactionsUseCase.java) + `GET /transactions`, permitindo perguntar à IA *"quais foram todos os meus gastos?"*;
4. **System prompt aprimorado** com regras explícitas (conversão reais → centavos, categorias disponíveis, respostas curtas em PT-BR): [system-message.st](src/main/resources/prompts/system-message.st).

_[Espaço para você adicionar a SUA própria evolução: nova categoria, validação de dados, total gasto por categoria...]_

---

## 8. O que aprendi durante o desafio

- **Tool Calling é a ponte IA ↔ negócio:** o modelo decide *qual* ferramenta chamar e *com quais parâmetros*, mas quem executa a regra é o caso de uso — a IA não inventa dados;
- **Arquitetura em camadas vale ouro:** trocar OpenAI por Ollama mexeu só em `build.gradle` + `application.properties`. Domínio e casos de uso ficaram intactos;
- **Prompt engineering importa:** especificar centavos, categorias e formato de resposta no *system message* melhora muito a precisão do modelo local;
- **Modelos locais funcionam de verdade:** llama3.2 consegue interpretar comandos em português e chamar ferramentas, com a vantagem de custo zero e privacidade total;
- _[adicione aqui o que VOCÊ aprendeu...]_

---

## 9. Evidências

<!-- Prints da interface, respostas da IA, saída dos testes... -->

- _[print da interface web com a resposta da IA]_
- _[saída do GET /transactions mostrando o registro criado pelo comando de texto]_
