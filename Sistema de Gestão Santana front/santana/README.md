# Sistema de Gestão Sant'Ana

Sistema de gestão para concessionária de veículos, desenvolvido para o curso de Engenharia de Software da Universidade Católica do Salvador (UCSAL). Projeto original de Gabriel de Brito Santana e Elton Figueiredo da Silva.

Os requisitos RF07 a RF13 usam o servidor HTTP do JDK, JDBC e PostgreSQL. A interface permanece em HTML, CSS e JavaScript. O projeto executa em Java 17 ou superior, sem Spring Boot e sem Maven. O driver JDBC já está em `lib/`.

## Executar

1. Use um JDK 17 ou superior. Disponibilize `java` e `javac` no `PATH` ou aponte `JAVA_HOME` para a pasta do JDK.
2. Inicie um servidor PostgreSQL e prepare as tabelas conforme as instruções abaixo. [Instalação oficial do PostgreSQL](https://www.postgresql.org/download/)
3. Copie `config.example.properties` para `config.properties`. Ajuste a URL JDBC, usuário e senha do banco.
4. Inicie o servidor na pasta do projeto e acesse [localhost:8080](http://localhost:8080). Encerre com `Ctrl+C`.

### Preparar o PostgreSQL

Em um banco novo, crie o banco `santana` e aplique os três scripts a partir da raiz do projeto. Substitua `postgres` pelo seu usuário do banco quando necessário.

```text
createdb -h localhost -U postgres santana
psql -h localhost -U postgres -d santana -v ON_ERROR_STOP=1 -c "SET client_encoding TO 'UTF8'" -f sql/criar_tabelas.sql -f sql/oficina.sql -f sql/notificacoes.sql
```

Em um banco Sant'Ana existente, aplique os scripts de oficina e notificações ao banco que contém suas vendas, veículos, clientes e test-drives.

```text
psql -h localhost -U postgres -d santana -v ON_ERROR_STOP=1 -c "SET client_encoding TO 'UTF8'" -f sql/oficina.sql -f sql/notificacoes.sql
```

### Iniciar o servidor

**Windows PowerShell**

```powershell
.\executar.ps1
```

Se a execução de scripts locais estiver desativada no Windows PowerShell, use `powershell -NoProfile -ExecutionPolicy Bypass -File .\executar.ps1` para este processo.

**Linux ou macOS**

```bash
bash executar.sh
```

Os scripts compilam o código em UTF-8 com alvo Java 17 e funcionam a partir de outra pasta quando chamados por caminho absoluto. A configuração, a pasta `web/` e os caminhos relativos são lidos a partir da raiz do projeto.

O script de criação do banco inclui contas para testes locais.

| Perfil | E-mail | Senha de demonstração |
| --- | --- | --- |
| Administrador | `admin@santana.com` | `Admin@123` |
| Gerente de Vendas | `gerente@santana.com` | `Gerente@123` |
| Vendedor | `vendedor@santana.com` | `Vendedor@123` |
| Recepcionista | `recepcao@santana.com` | `Recepcao@123` |

## Requisitos implementados

| Requisito | Uso |
| --- | --- |
| RF07 | Gere o PDF de uma venda concluída na tela de contrato ou via `GET /api/vendas/{id}/contrato.pdf`. |
| RF08 | Reserve um horário para o chassi de um veículo existente e um técnico cadastrado. A disponibilidade considera reservas sobrepostas do veículo e do técnico. |
| RF09 | Converta um agendamento em uma OS. O agendamento passa de `AGENDADO` a `CONVERTIDO` ou `CANCELADO`. A OS passa por `ABERTA` → `EM_EXECUCAO` → `CONCLUIDA` → `FATURADA`. |
| RF10 | Lance peças e serviços na OS em execução. Cada lançamento atualiza o subtotal. Os lançamentos de peças também baixam seu saldo em estoque. |
| RF11 | Emita uma fatura interna para a OS concluída. A alíquota configurada incide sobre o subtotal em reais e o imposto é arredondado a duas casas decimais. Se a solicitação for repetida, a API devolve a fatura existente. |
| RF12 | Enfileire notificações de confirmação, cancelamento, lembrete e faturamento a partir das operações da oficina. O servidor processa a fila para os canais habilitados. |
| RF13 | Abra **Relatórios** para consultar vendas, test-drives e oficina por período. O painel agrega os registros do PostgreSQL. |

### Contrato de venda

O template padrão fica em `src/main/resources/contrato-venda.txt`. Aponte `OFICINA_TEMPLATE` para outro arquivo UTF-8 para personalizar o texto. Marcadores aceitos no contrato de venda incluem `{{vendaId}}`, `{{propostaId}}`, `{{vendedor}}`, `{{comprador}}`, `{{documento}}`, `{{endereco}}`, `{{veiculo}}`, `{{ano}}`, `{{chassi}}`, `{{valor}}`, `{{pagamento}}` e `{{data}}`.

O gerador pagina o PDF em A4, usa fonte Courier e aceita os caracteres de Windows-1252, incluindo acentos do português. Marcadores desconhecidos ou caracteres fora dessa codificação retornam erro.

### Oficina pela API

Faça login com `POST /api/login`, passando `email` e `senha` em JSON. Use o token retornado em `Authorization: Bearer <token>` nas requisições seguintes. Cadastro de técnicos, alterações de estoque e faturamento exigem perfil Administrador ou Gerente de Vendas.

O arquivo [requisicoes.http](requisicoes.http) contém uma sequência de exemplos desde a venda até o relatório, com autenticação, agendamento, OS, baixa de estoque, faturamento e simulação de notificações.

| Método e caminho | Operação e campos |
| --- | --- |
| `GET /api/oficina/tecnicos`, `POST /api/oficina/tecnicos` | Liste ou cadastre um técnico com `nome`. |
| `GET /api/oficina/pecas`, `POST /api/oficina/pecas` | Liste ou cadastre uma peça com `codigo`, `descricao`, `valorUnitario` e `saldo`. |
| `POST /api/oficina/pecas/{id}/reposicao` | Reponha o estoque com `quantidade`. |
| `GET /api/oficina/agendamentos`, `POST /api/oficina/agendamentos` | Liste ou agende com `clienteId`, `chassi`, `tecnicoId`, `inicio` e `fim`. |
| `PUT /api/oficina/agendamentos/{id}/cancelamento` | Cancele um agendamento ativo. |
| `POST /api/oficina/agendamentos/{id}/os` | Converta o agendamento em OS. |
| `GET /api/oficina/os`, `GET /api/oficina/os/{id}` | Liste ou consulte uma OS. |
| `PUT /api/oficina/os/{id}/inicio` | Inicie a execução. |
| `POST /api/oficina/os/{id}/itens` | Lance `tipo: "PECA"`, `pecaId` e `quantidade`, ou `tipo: "SERVICO"`, `descricao`, `valorUnitario` e `quantidade`. |
| `PUT /api/oficina/os/{id}/conclusao` | Conclua a OS. |
| `POST /api/oficina/os/{id}/fatura` | Calcule o imposto e emita a fatura interna. |

Use horários ISO 8601 com deslocamento em `inicio` e `fim`, como `2030-06-12T10:00:00-03:00`. O início deve estar no futuro. O instante final da reserva pode ser o instante inicial da reserva seguinte. Use o chassi cadastrado no projeto. A fatura representa o valor apurado pela oficina para uma integração fiscal posterior.

### Relatórios

O endpoint `GET /api/dashboard` aceita `inicio` e `fim` em `AAAA-MM-DD`, com início incluído e fim excluído. Como alternativa de filtro, use `meses` para indicar a quantidade de meses até o mês corrente, inclusive. O padrão da API é o mês corrente em `America/Sao_Paulo` e cada consulta abrange até 120 meses.

O painel retorna totais, série mensal e os dez modelos mais vendidos. Cada indicador considera a data do próprio evento. Test-drives usam a data agendada e o status atual. A oficina usa a criação do agendamento, a abertura e conclusão da OS e a emissão da fatura. O faturamento da oficina inclui subtotal, imposto e total.

## Configuração da oficina e das notificações

Defina as variáveis no terminal usado para iniciar o servidor.

| Variável | Uso | Padrão |
| --- | --- | --- |
| `OFICINA_IMPOSTO` | Alíquota como decimal de `0` a `1`, com até seis casas decimais. `0.05` representa 5%. | `0.05` |
| `OFICINA_TEMPLATE` | Caminho de um template alternativo de contrato. | Template fornecido com o projeto |
| `OFICINA_INTERVALO_SEGUNDOS` | Segundos entre verificações da fila, de 1 a 86400. | `30` |
| `OFICINA_EMAIL_URL` e `OFICINA_SMS_URL` | Endpoints HTTP dos provedores ou adaptadores de e-mail e SMS. Configure os canais que deseja processar. | Canal sem envio real |
| `OFICINA_EMAIL_TOKEN` e `OFICINA_SMS_TOKEN` | Tokens Bearer opcionais dos endpoints de e-mail e SMS. | Sem token |
| `OFICINA_NOTIFICACOES_CONSOLE` | Use `true` com contatos fictícios para simular os canais sem endpoint no terminal. A simulação registra a notificação como processada. | `false` |

O lembrete vence 24 horas antes do horário de oficina quando o agendamento é feito com antecedência suficiente. A conversão em OS cancela o lembrete pendente. O cancelamento da reserva cancela confirmações e lembretes pendentes e enfileira a notificação de cancelamento.

A fila usa os contatos cadastrados dos clientes com consentimento. O agendador verifica a fila ao iniciar e depois de cada intervalo, processando até dez registros por canal a cada ciclo. Os canais desabilitados mantêm seus registros pendentes.

Cada endpoint precisa aceitar `POST` com `Content-Type: application/json; charset=utf-8` e `Idempotency-Key`. Se houver token configurado, será enviado `Authorization: Bearer <token>`. O corpo tem o seguinte formato.

```json
{
  "destino": "cliente@example.com",
  "assunto": "Agendamento confirmado",
  "mensagem": "Seu agendamento foi confirmado."
}
```

Em SMS, `destino` contém o telefone cadastrado. Uma resposta HTTP de 200 a 299 confirma a aceitação. O cliente HTTP usa tempo limite de 5 segundos para conexão e 10 segundos por requisição. O receptor deve tratar o mesmo `Idempotency-Key` como o mesmo envio, inclusive caso aceite uma requisição e a resposta se perca.

Uma falha de envio libera uma nova tentativa após um minuto, até cinco tentativas no total. Uma reserva de envio interrompida pode ser retomada após dois minutos, enquanto houver tentativas disponíveis. Os registros ficam na tabela `oficina_notificacoes` com os estados `PENDENTE`, `EM_ENVIO`, `ENVIADA`, `FALHOU` ou `CANCELADA`. Consulte o andamento no PostgreSQL com a seguinte consulta.

```sql
SELECT evento, referencia, canal, estado, tentativas, disponivel_em, enviado_em, ultimo_erro
FROM oficina_notificacoes
ORDER BY disponivel_em DESC
LIMIT 50;
```

## Testes

| Suíte | PowerShell | Linux ou macOS |
| --- | --- | --- |
| Testes sem PostgreSQL | `.\executar.ps1 teste` | `bash executar.sh teste` |
| Integração com PostgreSQL | `.\executar.ps1 teste-integracao` | `bash executar.sh teste-integracao` |

Os testes sem PostgreSQL usam arquivos temporários e um servidor HTTP local para simular o provedor. Para a suíte de integração, aponte `config.properties` para um banco PostgreSQL descartável. Use uma conta com permissão para criar esquemas. A suíte cria seu próprio esquema, executa os testes e remove o esquema ao encerrar.
