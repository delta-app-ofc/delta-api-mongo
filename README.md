# delta-api-mongo

Consulte [DOCKER-HELP.md](DOCKER-HELP.md) para usar o ambiente Docker local.

## Detector e API de alertas

O detector de consumo e a API de alertas estão implementados e desligados por padrão. A conexão de telemetria continua sendo usada para consumo e status; baseline e histórico de alertas usam `appMongoTemplate`, no banco `db_delta_app`.

Use [.env.example](.env.example) como referência de configuração. Antes de habilitar qualquer uma das funcionalidades, configure `MONGODB_APP_URI` para o cluster da aplicação e aplique os scripts de coleção, migração e índice do `delta-mongo-database`. A API não cria índices. O fallback atual para a URI de telemetria serve para ambientes com um único cluster; em produção, os clusters separados exigem a URI da aplicação.

| Configuração | Uso |
| --- | --- |
| `DELTA_DETECTION_ENABLED` | Habilita a avaliação após inserir uma janela de consumo |
| `DELTA_DETECTION_CONFIG_VERSION` | Versão dos parâmetros registrada na detecção |
| `DELTA_DETECTION_OWNER_URL` | URL base da API Postgres, sem o caminho `/delta/internal/device/{id}/owner` |
| `DELTA_DETECTION_OWNER_SERVICE_KEY` | Mesma `AUTH_SERVICE_KEY` da API Postgres |
| `MONGODB_APP_URI`, `MONGODB_APP_DATABASE` | Conexão do baseline e de `alerts_history` |
| `DELTA_ALERTS_ENABLED` | Habilita as rotas de alertas |
| `AUTH_PUBLIC_KEY` | Chave pública RSA do back-end, em PEM ou caminho de arquivo |
| `AUTH_ISSUER`, `AUTH_AUDIENCE` | Emissor e audiência esperados no JWT do back-end |
| `ALERTS_SERVICE_KEY` | Chave compartilhada com os serviços autorizados a escrever alertas |

Uma janela duplicada continua retornando 409 antes da detecção. Se o detector falhar após a inserção, o POST mantém 201 e registra a falha; `anomaly_detected=null` significa que a janela não foi avaliada. A consulta de cadastro usa timeout e cache; sem os dados do imóvel, a detecção continua com as limitações registradas na evidência/erro.

## Rotas de alertas

As rotas do usuário exigem `Authorization: Bearer <JWT RS256>`. O usuário vem do `sub` do token; um `user_id` enviado pelo cliente não altera esse usuário. As rotas internas exigem `X-Service-Key` com a `ALERTS_SERVICE_KEY`. A autenticação das rotas existentes de consumo e status permanece como antes.

| Método e rota | Comportamento |
| --- | --- |
| `GET /api/alerts` | Lista os alertas do usuário, mais novos primeiro; aceita `severity`, `include_dismissed`, `page` e `size` (até 100) |
| `PATCH /api/alerts/{id}/dismiss` | Dispensa o próprio alerta, mantendo a primeira data; outro usuário recebe 404 |
| `GET /api/alerts/leak-summary` | Resume litros/dia estimados e pontos por dispositivo; inclui vazamentos abertos e os disparados nas últimas 24 h |
| `POST /api/internal/alerts` | Cria alerta de `gluh` ou `system`; retorna 201 ao criar e 200 quando a chave já tem um alerta aberto |
| `POST /api/internal/alerts/resolve` | Encerra alertas abertos pela `dedup_key` com prefixo `gluh:` ou `system:`; repetir a chamada é permitido |

O resumo de vazamentos não inclui custo em reais. Alertas do detector usam templates em português, `source=detector` e chave `detector:{deviceId}:{rule}:{userId}`. O histórico é gravado independentemente do envio de push ou e-mail, que cabe ao componente de notificação.

O Gluh pode escrever `consumo_acima_esperado`, `previsao_ultrapassagem_meta`, `limite_ultrapassado`, `previsao_conta_disponivel`, `tarifa_atualizada` e `dica_personalizada`. Seus alertas usam `low` ou `medium`, com uma exceção confirmada em 02/11/2026: `limite_ultrapassado` também aceita `high`. O sistema escreve somente `tarifa_atualizada`, com `low` ou `medium`. As chaves internas devem começar com `gluh:{userId}:` ou `system:`, respectivamente.

Os contratos de requisição e resposta estão no Swagger (`/swagger-ui.html`). Para verificar o código, execute `./mvnw -B -ntp verify` (PowerShell: `.\mvnw.cmd -B -ntp verify`). Os testes com serviços reais continuam opt-in; a suíte padrão não valida configuração nem implantação em produção.
