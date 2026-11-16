Sistema de Gestão Sant'Ana

Sistema de gestão para concessionária de veículos, desenvolvido para o curso de Engenharia de Software da Universidade Católica do Salvador (UCSAL).

Autores: Gabriel de Brito Santana e Elton Figueiredo da Silva.

Situação atual:
Front-end pronto. Back-end em Java com os requisitos RF01, RF03, RF04, RF05 e RF06 implementados. Os demais requisitos ainda não foram feitos no back-end (RF02 já funciona como consulta de veículos; RF07 entrega os dados do contrato; RF13 e as integrações ainda não existem).

Onde estão os requisitos no código:
Procure por "requisito RF" nos arquivos da pasta src. Cada regra de negócio tem um comentário "requisito RFxx aqui" na linha correspondente.

Tecnologias:
HTML, CSS e JavaScript no front. Java 17 ou superior (servidor HttpServer do próprio JDK) e PostgreSQL no back-end. Não usa Spring nem Maven.

Estrutura:
web/ páginas, style.css e app.js
src/ código Java
sql/ script de criação das tabelas
lib/ coloque aqui o driver JDBC do PostgreSQL (arquivo .jar)
requisicoes.http requisições prontas para testar a API sem tela

Como rodar:
1. Instale JDK 17 ou superior e PostgreSQL. No VS Code instale o Extension Pack for Java.
2. Baixe o driver JDBC em jdbc.postgresql.org e coloque o arquivo .jar dentro da pasta lib.
3. No PostgreSQL crie o banco: CREATE DATABASE santana;
4. Conectado no banco santana, execute o arquivo sql/criar_tabelas.sql.
5. Copie config.example.properties para config.properties e coloque a sua senha do PostgreSQL.
6. Abra a pasta santana no VS Code (a pasta que contém src e web) e rode a classe Main.
7. Acesse http://localhost:8080 no navegador.

Usuários de teste (criados pelo script SQL):
admin@santana.com com senha Admin@123 (Administrador)
gerente@santana.com com senha Gerente@123 (Gerente de Vendas)
vendedor@santana.com com senha Vendedor@123 (Vendedor)
recepcao@santana.com com senha Recepcao@123 (Recepcionista)

Permissões:
Cadastrar veículo e alterar status: Administrador e Gerente de Vendas.
Cadastrar cliente e agendar test-drive: Vendedor, Recepcionista, Gerente e Administrador.
Alterar status do test-drive e criar proposta: Vendedor, Gerente e Administrador.
Aprovar desconto acima da alçada: Gerente e Administrador.

Regras de demonstração:
Alçada de desconto: o vendedor aprova sozinho até 3 por cento. Acima disso a proposta fica aguardando o gerente. O máximo para qualquer perfil é 10 por cento.
Test-drive: dura 60 minutos e pode começar entre 08:00 e 17:00.
Validação da CNH: é simulada. Cliente com CNH anexada no cadastro é considerado válido. Na versão real seria uma consulta à API do Detran.

Modo sem back-end:
No início de web/app.js, trocar USE_MOCK para true faz o front funcionar sozinho com dados no navegador (nesse modo os requisitos de test-drive e proposta não existem, pois não há tela para eles).

Documentação:
Os requisitos e os diagramas do sistema estão na Documentação Técnica Sant'Ana.
