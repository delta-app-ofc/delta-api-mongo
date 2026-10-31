# Testes e cobertura

Execute a suíte automatizada sem credenciais:

    ./mvnw -B -ntp clean verify
    ./mvnw -B -ntp dependency:copy-dependencies -DincludeScope=test -DoutputDirectory=target/dependency

O JaCoCo gera o relatório XML em target/site/jacoco/jacoco.xml; o Surefire grava os resultados em
target/surefire-reports/. Os testes de integração que exigem um MongoDB real continuam opt-in e são ignorados
na execução padrão. A suíte padrão usa mocks e testes HTTP sem persistência externa.
O teste de contexto usa somente o endpoint local isolado 127.0.0.1:27018 no banco delta_test e não executa
consultas ou gravações. Os testes Live continuam opt-in e não rodam no workflow.
