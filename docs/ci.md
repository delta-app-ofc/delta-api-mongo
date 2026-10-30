# CI técnico

O workflow `CI técnico` executa `bash ./mvnw --batch-mode --no-transfer-progress test` com Java 17 em pull requests e por acionamento manual. O Maven compila o projeto e executa a suíte padrão, incluindo testes de controladores, repositório com mocks e inicialização do contexto.

Os testes HTTP que usam MongoDB real são opt-in e não são habilitados nesse workflow; ele não valida integração com MongoDB, Atlas ou serviços externos.

Para executar a mesma suíte localmente, instale Java 17 e rode `bash ./mvnw test` no Linux/macOS ou `mvnw.cmd test` no Windows. O Maven Wrapper baixa o Maven e as dependências necessárias.
