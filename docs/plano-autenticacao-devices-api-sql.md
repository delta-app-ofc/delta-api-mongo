# Plano de credenciais de dispositivos — Delta API SQL

## Objetivo e contexto

A Delta API SQL já cadastra dispositivos, mas ainda não gera credenciais ou tokens. A Delta API Mongo recebe e armazena telemetria. Quem envia essa telemetria é um device, não um usuário.

Implementar na API SQL a emissão, gestão e validação de uma API key individual por device. A API Mongo usará essa validação para identificar o dispositivo que fez a requisição.

Este documento é um plano de implementação para continuar o trabalho no repositório da API SQL. A estrutura, o banco, o modelo de device e a segurança desse repositório ainda precisam ser inspecionados. Os nomes de tabelas, campos e endpoints abaixo são propostas; devem ser adaptados ao padrão existente, preservando o contrato acordado entre as APIs.

## Decisões para a primeira versão

- A API SQL é a fonte oficial de identidade e estado dos dispositivos.
- Cada device terá, no máximo, uma credencial válida por vez.
- A autenticação inicial usará API keys; não será necessário implementar login de devices, JWT ou refresh tokens.
- O dispositivo enviará sua chave à API Mongo no header `Authorization: Bearer <device_api_key>`, via HTTPS.
- A chave completa só será entregue na emissão ou troca. Consultas posteriores nunca retornarão a chave nem seu hash.
- A API Mongo consultará a SQL a cada autenticação, sem cache na primeira versão. Uma validação já realizada não cancela uma requisição em andamento caso o device seja bloqueado em seguida.
- A credencial que autentica a API Mongo perante a SQL será independente das chaves dos dispositivos.

## 1. Inspecionar o projeto SQL antes de alterar código

- Ler as instruções do repositório e identificar a arquitetura dos módulos.
- Localizar o cadastro, a entidade, o repositório, os DTOs e os testes de devices.
- Identificar o tipo e o significado do identificador do device. A API Mongo deve usar o mesmo identificador canônico; não criar um segundo ID para o mesmo dispositivo.
- Identificar como o estado ativo/inativo e a exclusão de devices são representados.
- Verificar a segurança administrativa existente, inclusive a autorização para operar sobre cada device.
- Identificar a ferramenta de migrations e o padrão de erros e documentação OpenAPI.
- Verificar se o cadastro pode ser mantido como está, adicionando uma operação separada de provisionamento da chave.

Preferir o provisionamento separado na primeira versão: ele atende devices novos e já cadastrados sem mudar silenciosamente o contrato de cadastro atual.

## 2. Persistência e migration

Criar uma estrutura de credenciais vinculada ao cadastro existente. Modelo conceitual:

| Campo | Finalidade |
| --- | --- |
| `id` | Identificador da credencial, distinto da chave secreta |
| `device_id` | Referência ao identificador canônico do device |
| `key_hash` | Hash da chave, com índice único para consulta |
| `created_at` | Data de emissão |
| `revoked_at` | Data de revogação; nula enquanto não revogada |
| `expires_at` | Expiração opcional; nula se não houver expiração configurada |

Requisitos:

- Usar o mesmo tipo de ID e a mesma convenção de datas do projeto; documentar datas em UTC no contrato HTTP.
- Preservar os devices e dados existentes. Não gerar chaves em massa em uma migration.
- Impedir duas credenciais válidas para o mesmo device, inclusive em operações concorrentes. Usar transação e bloqueio/constraint apropriados ao banco encontrado.
- Manter o histórico de credenciais revogadas enquanto o device existir. Definir o comportamento da exclusão conforme o padrão do projeto, garantindo que um device excluído não autentique.
- Tratar expiração explicitamente no serviço; não depender de limpeza periódica de registros para negar uma chave expirada.

## 3. Geração e armazenamento da chave

- Gerar pelo menos 32 bytes aleatórios com um gerador criptograficamente seguro; em Java, usar `SecureRandom`.
- Codificar a parte aleatória em Base64 URL-safe sem padding. Um prefixo fixo, como `delta_dev_`, pode facilitar a identificação do formato.
- A chave deve ser gerada no servidor. O cliente não poderá escolher seu valor, ID, hash ou permissões.
- Para essa chave aleatória de alta entropia, calcular SHA-256 sobre o valor completo e persistir somente o hash. Não reutilizar esse procedimento para senhas humanas.
- Não incluir a chave em URLs, logs, traces, mensagens de erro, auditoria, listagens ou consultas de devices.
- Aplicar `Cache-Control: no-store` às respostas de emissão, troca e validação.
- Se a resposta de emissão for perdida, a chave não será recuperável: será necessário executar a troca por uma nova.

Auditar emissão, troca e revogação com o identificador do device, o identificador da credencial, o responsável pela operação e a data, sem registrar o segredo.

## 4. Operações administrativas de credenciais

As rotas abaixo são propostas e devem respeitar o padrão da API SQL. Todas exigem autenticação administrativa e autorização sobre o device. Não deixar públicas nem permitir acesso usando apenas uma chave de device.

### Emitir a primeira chave

`POST /api/devices/{device_id}/credentials`

- Exigir que o device exista e esteja ativo.
- Permitir a operação para dispositivos já cadastrados, inclusive os anteriores à migration.
- Recusar se já houver uma credencial válida; a substituição deve ocorrer pela operação de troca.
- Após uma revogação ou expiração, permitir nova emissão para um device ativo.
- Persistir o hash e devolver a chave completa apenas nesta resposta.

Resposta proposta: `201 Created`.

```json
{
  "credential_id": "<id-da-credencial>",
  "device_id": "<id-canonico-do-device>",
  "api_key": "<chave-gerada-entregue-uma-unica-vez>",
  "created_at": "2026-10-05T15:00:00Z",
  "expires_at": null
}
```

Os IDs são exemplos em texto. Definir seus tipos JSON conforme o modelo SQL e documentá-los para a integração.

### Trocar a chave

`POST /api/devices/{device_id}/credentials/rotate`

- Exigir um device ativo e uma credencial válida existente.
- Gerar uma nova chave e revogar a anterior na mesma transação.
- Retornar `200 OK` com a mesma estrutura da emissão.
- Não manter período de sobreposição na primeira versão. A chave antiga deixa de ser aceita após o commit; o dispositivo precisará receber a nova chave por um processo de provisionamento autorizado.
- Se não houver credencial válida, retornar conflito e orientar o uso da emissão.
- Tratar operações concorrentes sem deixar duas chaves válidas e sem desfazer a revogação da antiga em caso de falha parcial.

### Revogar a chave

`DELETE /api/devices/{device_id}/credentials/current`

- Revogar a credencial atual sem excluir o device.
- Retornar `204 No Content`; repetir a revogação de um device existente deve continuar retornando `204`.
- Não exigir que o device esteja ativo para revogar sua chave.

### Consultar metadados

Usar o padrão existente de consulta do device ou criar uma rota administrativa específica para informar se há credencial válida e suas datas. Nunca retornar `api_key` nem `key_hash`.

### Erros administrativos

| Situação | Status proposto |
| --- | --- |
| Device inexistente | `404` |
| Device inativo em emissão ou troca | `409` |
| Credencial válida já existe na emissão | `409` |
| Não há credencial válida para trocar | `409` |
| Administrador sem autenticação | `401` |
| Administrador autenticado sem autorização sobre a operação/device | `403` |

## 5. Endpoint interno de validação

Proposta: `POST /api/internal/device-auth/validate`.

Esse endpoint será chamado pela API Mongo, não diretamente pelo dispositivo. Ele precisa de autenticação entre serviços e não deve ficar protegido apenas pelo nome `internal` ou por regras de CORS.

### Autenticação da API Mongo perante a SQL

- Reutilizar a autenticação entre serviços, se ela já existir.
- Caso não exista, implementar uma credencial exclusiva da integração, configurada por ambiente/gerenciador de segredos. Nenhum valor real deve ser versionado.
- Nesta proposta, usar `Authorization: Bearer <mongo_service_credential>` no endpoint interno.
- A SQL valida essa credencial independentemente da chave do device. Uma chave de dispositivo nunca deve autorizar essa rota.
- Restringir a credencial de integração ao endpoint de validação; ela não deve permitir cadastrar devices ou emitir chaves.
- Não reutilizar automaticamente a política de sessão de usuários ou desabilitar CSRF globalmente. Integrar a rota ao mecanismo adequado para chamadas entre serviços.
- Documentar configuração e procedimento de troca da credencial entre serviços.
- Usar HTTPS e, quando disponível, restringir a comunicação na infraestrutura.

### Requisição

```http
POST /api/internal/device-auth/validate
Authorization: Bearer <mongo_service_credential>
Content-Type: application/json
```

```json
{
  "api_key": "<device_api_key>"
}
```

O header autentica o serviço chamador. O corpo contém a chave do device a ser validada. Essa distinção deve estar explícita no código, nos testes e na documentação.

### Resposta para device autorizado

`200 OK`:

```json
{
  "valid": true,
  "device_id": "<id-canonico-do-device>",
  "credential_id": "<id-da-credencial>",
  "permissions": ["telemetry:write"]
}
```

`permissions` terá inicialmente apenas `telemetry:write`, definido pelo servidor. A autenticação do device não deve conceder automaticamente leitura de telemetria, administração ou acesso a outros devices.

### Resposta para device não autorizado

Com o serviço chamador autenticado, devolver `200 OK`:

```json
{
  "valid": false
}
```

Usar a mesma resposta para chave desconhecida, revogada, expirada, device inexistente, excluído ou inativo. Não devolver identidade nem explicar qual dessas condições ocorreu. Se o formato for inválido, mas o campo for uma string presente dentro do limite aceito, também devolver `valid: false`.

### Outros resultados

| Situação | Resposta SQL |
| --- | --- |
| Credencial entre serviços ausente ou inválida | `401` |
| Serviço autenticado, mas sem permissão para validar | `403` |
| JSON inválido, campo obrigatório ausente ou tipo incorreto | `400` |
| Limite de chamadas excedido, se configurado | `429` |
| Dependência indisponível | Erro de serviço conforme o padrão do projeto, sem converter em `valid: false` |

Limitar o tamanho de `api_key` (proposta: 256 caracteres). O payload precisa ser excluído da captura de logs HTTP, APM e traces. Documentar também a política de limites de chamadas adotada.

### Lógica de validação

1. Autenticar e autorizar o serviço chamador.
2. Validar a estrutura do corpo e os limites de tamanho.
3. Calcular o hash da chave recebida e procurar a credencial correspondente.
4. Verificar revogação e expiração.
5. Verificar a existência e o estado atual do device associado.
6. Retornar a identidade canônica e as permissões apenas se todas as verificações passarem.

Não aceitar um `device_id` informado pelo chamador como prova de identidade. O vínculo será obtido exclusivamente a partir da credencial persistida.

## 6. Estado do dispositivo

- Inativar um device impede novas autenticações, mesmo que sua chave ainda não esteja revogada.
- Na primeira versão, a inativação é um bloqueio reversível: reativar o device permite reutilizar uma chave ainda válida. A revogação é definitiva para aquela chave.
- Excluir um device impede sua autenticação. Aplicar a política de integridade/histórico compatível com o projeto.
- Não implementar cache de validação nesta etapa. Qualquer cache futuro deve definir explicitamente o atraso máximo para bloqueio e revogação.

## 7. Organização e configuração

- Seguir os padrões de controller, service, repository, DTOs, tratamento de erros e testes do projeto SQL.
- Separar a lógica de gestão de credenciais da lógica de autenticação do serviço Mongo.
- Usar DTOs explícitos para evitar serializar entidades que contenham o hash.
- Documentar no `.env.example` as configurações de integração com placeholders, nunca segredos reais.
- Manter as migrations e o contrato OpenAPI versionados.
- Não incluir dependências de JWT ou novos fluxos de login sem uma necessidade adicional.

## 8. Testes necessários

### Gestão de credenciais

- Emitir chave para device novo e já existente.
- Recusar device inexistente ou inativo.
- Recusar emissão duplicada enquanto houver chave válida.
- Verificar que o banco contém o hash, não a chave completa.
- Verificar que consultas e respostas de erro não expõem chave ou hash.
- Trocar a chave: a antiga falha e a nova autentica.
- Revogar a chave, repetir a revogação e emitir uma nova posteriormente.
- Simular concorrência de emissão/troca e verificar a regra de uma credencial válida por device.
- Verificar rollback em falhas da troca para evitar estado parcial.
- Negar operações a chamadores sem a autorização administrativa necessária.

### Validação interna

- Aceitar chave válida de device ativo e retornar o ID canônico correto.
- Recusar chave desconhecida, revogada ou expirada e device inativo/excluído.
- Não retornar identidade nos resultados inválidos.
- Exigir credencial entre serviços; rejeitar chave de device usada no header dessa rota.
- Impedir que a credencial do serviço Mongo administre devices ou chaves.
- Diferenciar falha de infraestrutura de chave inválida.
- Cobrir JSON inválido, campos ausentes e limite de tamanho.
- Validar bloqueio e reativação conforme a política descrita.

Usar testes unitários para regras de negócio e testes de integração para autorização HTTP, migrations, persistência e transações. Executar também os testes existentes afetados.

## 9. Contrato que será entregue à API Mongo

Ao concluir a SQL, fornecer:

- URL, método e schema do endpoint interno.
- Tipo e significado do `device_id`, incluindo correspondência com o campo de telemetria existente.
- Configuração da credencial entre serviços e procedimento de troca.
- Schemas de sucesso e falha, status HTTP e exemplos sem segredos reais.
- Política de permissões, bloqueio, revogação e expiração.
- Limites de chamadas, quando houver, e comportamento esperado em falhas.
- Um cenário reproduzível em ambiente de desenvolvimento com um device provisionado por um operador autorizado.

Na Mongo, a chave chegará no header do device e será encaminhada no corpo da chamada interna. A Mongo vinculará o lote ao `device_id` devolvido pela SQL e rejeitará divergência com o payload.

Resultado `valid: false` será convertido em `401` pela Mongo; falta de permissão para uma operação será `403`. Timeout, falha de conectividade, erro de serviço ou resposta inválida da SQL não podem liberar a requisição. A Mongo deverá responder com erro de indisponibilidade adequado, em vez de alegar que a chave do device está incorreta. Falhas de autenticação entre serviços também são falhas da integração.

## 10. Ordem de execução e critérios de conclusão

1. Inspecionar o repositório SQL e ajustar este plano ao modelo existente.
2. Fixar o contrato de IDs, rotas, schemas e segurança entre serviços.
3. Implementar migration e persistência das credenciais.
4. Implementar emissão, troca e revogação com autorização administrativa.
5. Implementar autenticação entre serviços e validação interna.
6. Executar testes, revisar exposição de segredos e atualizar a documentação.
7. Entregar o contrato para iniciar a integração na API Mongo.

Checklist de conclusão:

- [ ] Cadastro existente continua funcionando e seus dados foram preservados.
- [ ] Devices existentes podem receber uma credencial sem novo cadastro.
- [ ] Chaves são geradas com aleatoriedade criptográfica e persistidas apenas como hash.
- [ ] Emissão, troca e revogação têm autorização administrativa.
- [ ] Há, no máximo, uma credencial válida por device, inclusive sob concorrência.
- [ ] Chaves antigas, revogadas e expiradas deixam de autenticar.
- [ ] Device inativo ou excluído não autentica.
- [ ] Validação interna exige uma credencial exclusiva do serviço chamador.
- [ ] Credencial do serviço Mongo não permite gestão administrativa.
- [ ] Respostas e logs não expõem chaves ou hashes.
- [ ] Testes relevantes e testes existentes afetados passam.
- [ ] Contrato OpenAPI e configurações de ambiente estão documentados.
- [ ] Integração pode ser exercitada em desenvolvimento.

## Fora do escopo inicial

- Login de usuários, login social e recuperação de senha.
- JWT, refresh tokens, OAuth e autenticação mTLS de devices.
- Cadastro público automático de dispositivos.
- Duplicação de cadastro na API Mongo.
- Cache de validação e sobreposição de chaves durante a troca.
- Implementação da proteção das rotas de telemetria no repositório SQL; essa parte será feita na Mongo.

## Referências

As medidas de HTTPS, proteção de segredos em transporte/logs e gestão de revogação seguem as orientações gerais da OWASP. O contrato de API e as escolhas de persistência deste documento são propostas específicas para o Delta.

- [OWASP — REST Security](https://cheatsheetseries.owasp.org/cheatsheets/REST_Security_Cheat_Sheet.html)
- [OWASP — Secrets Management](https://cheatsheetseries.owasp.org/cheatsheets/Secrets_Management_Cheat_Sheet.html)

## Prompt para continuar no workdir SQL

> Implemente o provisionamento e a validação de credenciais de devices conforme este documento. Comece lendo as instruções do repositório e inspecionando o cadastro, o modelo de device, as migrations e a segurança existentes. Preserve o cadastro atual e adapte nomes e tipos ao projeto. Desenvolva emissão, troca e revogação administrativa de API keys, armazenamento apenas do hash e um endpoint interno protegido para a API Mongo validar a chave e obter o identificador canônico do device. Não implemente login de usuários nem JWT nesta etapa. Se faltar autorização administrativa, trate essa dependência antes de expor operações de credenciais. Execute os testes relevantes e documente o contrato final e as configurações necessárias para a integração.
