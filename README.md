# Delta · API de telemetria MongoDB

![Java](https://img.shields.io/badge/Java-17-ED8B00?logo=openjdk&logoColor=white)
![Spring Boot](https://img.shields.io/badge/Spring%20Boot-4.1.1-6DB33F?logo=springboot&logoColor=white)
![MongoDB](https://img.shields.io/badge/MongoDB-Spring%20Data-47A248?logo=mongodb&logoColor=white)
![OpenAPI](https://img.shields.io/badge/springdoc-3.1.1-6BA539?logo=swagger&logoColor=white)

A API de telemetria MongoDB registra os resumos de consumo enviados pelo hardware e mantém o estado mais recente de cada dispositivo. Construída com Spring Boot, ela oferece endpoints para gravar e consultar esses dados.

**Tags:** `delta` · `api-rest` · `spring-boot` · `java-17` · `mongodb` · `telemetria-iot` · `openapi`

## Sumário

- [Visão geral](#visão-geral)
- [Fluxo da telemetria](#fluxo-da-telemetria)
- [Recursos HTTP](#recursos-http)
- [Configuração e execução](#configuração-e-execução)
- [Testes e imagem Docker](#testes-e-imagem-docker)
- [Estrutura do repositório](#estrutura-do-repositório)
- [Repositórios relacionados](#repositórios-relacionados)

## Visão geral

| Área | Responsabilidade |
|---|---|
| Resumo de consumo | Receber e consultar documentos de `consumption_summary`, filtrados por dispositivo e período. |
| Estado do hardware | Atualizar e consultar `device_status`, mantendo o registro mais recente de cada `device_id`. |
| Validação | Validar os DTOs de entrada e os campos identificadores antes de persistir. |
| Persistência | Usar Spring Data MongoDB no banco `db_delta_telemetry`. |
| Operação | Expor health check, documentação OpenAPI e tratamento consistente de erros. |

A base usada pelo serviço é definida em `MONGODB_TELEMETRY_DATABASE` e a conexão em `MONGODB_TELEMETRY_URI`.

## Fluxo da telemetria

1. O dispositivo ou componente de integração envia uma janela de consumo já consolidada.
2. O controller valida a requisição e encaminha o caso de uso para o serviço do módulo.
3. O mapper e o repositório convertem e persistem o documento no MongoDB.
4. Consultas recuperam os resumos por identificador, dispositivo ou intervalo; atualizações de status sobrescrevem o estado atual daquele dispositivo.

Os repositórios definem índices para as consultas dos módulos. O cadastro relacional dos dispositivos fica no PostgreSQL e é mantido pela API correspondente.

## Recursos HTTP

| Método | Rota | Uso |
|---|---|---|
| `POST` | `/api/telemetry/consumption-summary` | Registra uma janela consolidada de consumo. |
| `GET` | `/api/telemetry/consumption-summary/{id}` | Consulta um resumo pelo identificador. |
| `GET` | `/api/telemetry/consumption-summary?device_id=...&start=...&end=...` | Consulta resumos paginados; `start` e `end` usam data/hora ISO 8601. |
| `POST` | `/api/telemetry/device-status` | Recebe o estado atual de um dispositivo. |
| `GET` | `/api/telemetry/device-status?device_id=...` | Consulta o estado atual do dispositivo indicado. |
| `GET` | `/health` | Verifica a disponibilidade da API e do MongoDB de telemetria. |

A especificação completa dos parâmetros e dos corpos está em `/swagger-ui.html`; o documento OpenAPI está em `/v3/api-docs`.

## Configuração e execução

Requisitos: Java 17, o Maven Wrapper incluído neste repositório e acesso a uma instância MongoDB compatível. A aplicação lê as variáveis do arquivo `.env` no desenvolvimento local.

### 1. Preparar o ambiente

Copie o modelo e configure a conexão com a instância MongoDB do ambiente. Mantenha a URI e as credenciais no `.env` local.

~~~bash
cp .env.example .env
~~~

As variáveis disponíveis são `MONGODB_TELEMETRY_URI`, `MONGODB_TELEMETRY_DATABASE` e `SERVER_PORT`. O exemplo já documenta os valores esperados; use credenciais próprias fora de um ambiente local descartável.

### 2. Iniciar a API

Linux/macOS:

~~~bash
./mvnw spring-boot:run
~~~

Windows PowerShell:

~~~powershell
.\mvnw.cmd spring-boot:run
~~~

A porta padrão é `8081`; `SERVER_PORT` permite alterá-la. Com a API iniciada, abra `http://localhost:8081/swagger-ui.html` ou consulte `http://localhost:8081/health`.

## Testes e imagem Docker

Execute a suíte Maven padrão, que não exige credenciais do MongoDB:

~~~bash
./mvnw -B -ntp clean verify
~~~

Os testes live que dependem de uma instância MongoDB são opt-in. Consulte [TESTING.md](TESTING.md) para seus limites e relatórios. O Dockerfile tem um estágio específico para executar a suíte:

~~~bash
docker build --no-cache --target test -t delta-api-mongo:test .
~~~

Para empacotar a imagem de execução:

~~~bash
docker build -t delta-api-mongo:local .
~~~

Veja [DOCKER-HELP.md](DOCKER-HELP.md) para os comandos e a interpretação do resultado.

## Estrutura do repositório

| Caminho | Conteúdo |
|---|---|
| `src/main/java/.../modules/consumption/` | Controller, serviço, mapper, documentos, repositório e índices de consumo. |
| `src/main/java/.../modules/device_status/` | Controller, serviço, mapper, documentos, repositório e índices do status do dispositivo. |
| `src/main/java/.../common/` | Configuração Mongo, OpenAPI, health check, validação de identificadores e erros comuns. |
| `src/main/resources/application.properties` | Porta, conexão Mongo, banco padrão e caminhos OpenAPI. |
| `src/test/java/` | Testes de controllers, serviços, repositórios, índices e cenários live opt-in. |
| `pom.xml` e `mvnw` | Dependências, Java 17 e execução reprodutível com Maven Wrapper. |
| `Dockerfile` | Estágios para testes e execução da API. |
| `.github/workflows/` | Testes, análise Sonar e verificações de Pull Request. |

## Repositórios relacionados

- [delta-api-postgres](https://github.com/delta-app-ofc/delta-api-postgres): cadastro, autenticação e dados relacionais.
- [delta-mongo-database](https://github.com/delta-app-ofc/delta-mongo-database): scripts, contratos e migrations das coleções MongoDB.
- [delta-handbook](https://github.com/delta-app-ofc/delta-handbook): documentação geral do Projeto Delta.