# Docker — delta-api-mongo

## Objetivo

Construir a imagem da API ou executar a suíte Maven padrão em container. Os testes live continuam opt-in.

## Pré-requisitos

Docker Engine em execução. Execute os comandos na raiz deste repositório.

## Executar

```bash
docker build --no-cache --target test -t delta-api-mongo:test .
# Para empacotar a imagem de execução:
docker build -t delta-api-mongo:local .
```

## Conferir o resultado e encerrar

Confira o resultado da suíte Maven e o código de saída do build. `--no-cache` força uma nova execução dos testes. Esta verificação usa Dockerfile diretamente, sem Compose. O empacotamento da imagem de execução não roda a suíte. Não habilite testes live contra serviços oficiais para testar o build local.

