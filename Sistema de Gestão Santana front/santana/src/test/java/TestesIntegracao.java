import java.math.BigDecimal;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

public final class TestesIntegracao {
    private record Dados(long cliente, long tecnico, long outroTecnico, long venda, long proposta) {}

    @FunctionalInterface
    private interface Operacao {
        void executar() throws Exception;
    }

    private TestesIntegracao() {}

    public static void main(String[] args) throws Exception {
        String esquema = "oficina_teste_" + UUID.randomUUID().toString().replace("-", "");
        try (Connection controle = Banco.abrir()) {
            executarSql(controle, "CREATE SCHEMA " + esquema);
            try {
                executarTestes(esquema);
                try (Connection c = conectar(esquema)) {
                    NotificacaoIntegracaoTeste.executar(c);
                }
            } finally {
                executarSql(controle, "DROP SCHEMA " + esquema + " CASCADE");
            }
        }
        System.out.println("Testes de integração RF07 a RF13 passaram.");
    }

    private static void executarTestes(String esquema) throws Exception {
        try (Connection c = conectar(esquema)) {
            for (String arquivo : List.of("criar_tabelas.sql", "oficina.sql", "notificacoes.sql")) {
                executarSql(c, Files.readString(Path.of("sql", arquivo)));
            }
            c.setAutoCommit(false);
            Dados dados = preparar(c);
            testarContrato(c, dados.venda());
            c.commit();

            OffsetDateTime inicio = OffsetDateTime.now(ZoneOffset.UTC).plusDays(3).withNano(0);
            long agendamento = testarAgenda(c, dados, inicio);
            c.commit();
            testarAgendaConcorrente(esquema, dados, inicio.plusHours(3));

            long os = testarOrdemEEstoque(c, agendamento);
            long fatura = testarFaturamento(c, os);
            testarRelatorio(c, dados, os, fatura);
            c.commit();
        }
    }

    private static Connection conectar(String esquema) throws SQLException {
        Connection c = Banco.abrir();
        try {
            executarSql(c, "SET search_path TO " + esquema + ", public");
            executarSql(c, "SET lock_timeout = '8s'");
            executarSql(c, "SET statement_timeout = '12s'");
            return c;
        } catch (SQLException erro) {
            c.close();
            throw erro;
        }
    }

    private static void executarSql(Connection c, String sql) throws SQLException {
        try (var comando = c.createStatement()) {
            comando.execute(sql);
        }
    }

    private static Dados preparar(Connection c) throws SQLException {
        long cliente = id(ClienteService.criar(c, Map.of(
                "nome", "João Gonçalves", "cpf", "52998224725", "consentimento", true,
                "email", "joao@example.invalid", "telefone", "+5511999990000")));
        Map<String, Object> usuario = Banco.um(c, "SELECT id, nome, perfil FROM usuarios WHERE email=?", "vendedor@santana.com");
        Sessao vendedor = new Sessao(id(usuario), (String) usuario.get("nome"), (String) usuario.get("perfil"));
        long veiculo = id(Banco.um(c, "SELECT id FROM veiculos WHERE chassi=?", "CH000001"));
        long proposta = id(PropostaService.criar(c, Map.of(
                "clienteId", cliente, "veiculoId", veiculo, "pagamento", "À vista"), vendedor));
        long venda = id(PropostaService.fecharVenda(c, proposta));
        long tecnico = id(OficinaService.cadastrarTecnico(c, Map.of("nome", "Técnica 1")));
        long outro = id(OficinaService.cadastrarTecnico(c, Map.of("nome", "Técnico 2")));
        return new Dados(cliente, tecnico, outro, venda, proposta);
    }

    private static void testarContrato(Connection c, long venda) throws Exception {
        Path template = Path.of("src", "main", "resources", "contrato-venda.txt");
        byte[] bytes = ContratoService.gerar(c, venda, template);
        String pdf = new String(bytes, StandardCharsets.US_ASCII);
        confirmar(pdf.startsWith("%PDF-1.4\n"), "cabeçalho do PDF da venda");
        for (String linha : List.of(
                "Comprador(a): João Gonçalves", "CPF/CNPJ: 52998224725", "Endereço: Não informado",
                "Responsável pela venda: Vendedor Teste", "Condição de pagamento: À vista", "Chassi: CH000001")) {
            String texto = HexFormat.of().formatHex(linha.getBytes(Charset.forName("windows-1252")));
            confirmar(pdf.contains("<" + texto + "> Tj"), "campo do PDF: " + linha);
        }
        esperarErro(404, () -> ContratoService.gerar(c, Long.MAX_VALUE, template));
    }

    private static long testarAgenda(Connection c, Dados dados, OffsetDateTime inicio) throws Exception {
        Map<String, Object> reserva = OficinaService.agendar(c, horario(dados, "CH000001", dados.tecnico(), inicio));
        igual("AGENDADO", reserva.get("estado"), "novo agendamento");
        long principal = id(reserva);
        fila(c, "OFICINA_AGENDADO", principal, "PENDENTE");
        fila(c, "OFICINA_LEMBRETE", principal, "PENDENTE");
        Map<String, Object> lembretes = Banco.um(c, """
                SELECT count(*) AS quantidade FROM oficina_notificacoes
                WHERE evento='OFICINA_LEMBRETE' AND referencia=? AND disponivel_em=?
                """, Long.toString(principal), inicio.minusHours(24));
        numero(2, lembretes.get("quantidade"), "lembretes agendados 24 horas antes");
        esperarErro(409, () -> OficinaService.agendar(c, horario(dados, "CH000001", dados.outroTecnico(), inicio)));
        esperarErro(409, () -> OficinaService.agendar(c, horario(dados, "CH000002", dados.tecnico(), inicio)));
        esperarErro(404, () -> OficinaService.agendar(c, horario(dados, "DESCONHECIDO", dados.tecnico(), inicio)));
        esperarErro(400, () -> OficinaService.agendar(c,
                horario(dados, "CH000002", dados.tecnico(), OffsetDateTime.now(ZoneOffset.UTC).minusHours(2))));

        Map<String, Object> intervaloVizinho = horario(dados, "CH000001", dados.tecnico(), inicio.plusHours(1));
        long vizinho = id(OficinaService.agendar(c, intervaloVizinho));
        igual("CANCELADO", OficinaService.cancelar(c, vizinho).get("estado"), "cancelamento");
        fila(c, "OFICINA_AGENDADO", vizinho, "CANCELADA");
        fila(c, "OFICINA_LEMBRETE", vizinho, "CANCELADA");
        fila(c, "OFICINA_CANCELADO", vizinho, "PENDENTE");
        esperarErro(409, () -> OficinaService.abrirOs(c, vizinho));
        igual("AGENDADO", OficinaService.agendar(c, intervaloVizinho).get("estado"), "reuso do intervalo cancelado");
        return principal;
    }

    private static Map<String, Object> horario(Dados dados, String chassi, long tecnico, OffsetDateTime inicio) {
        return Map.of("chassi", chassi, "clienteId", dados.cliente(), "tecnicoId", tecnico,
                "inicio", inicio.toString(), "fim", inicio.plusHours(1).toString());
    }

    private static void testarAgendaConcorrente(String esquema, Dados dados, OffsetDateTime inicio) throws Exception {
        var pool = Executors.newFixedThreadPool(2);
        var barreira = new CyclicBarrier(2);
        try {
            var primeira = pool.submit(() -> reservarConcorrente(esquema, dados, "CH000002", inicio, barreira));
            var segunda = pool.submit(() -> reservarConcorrente(esquema, dados, "CH000003", inicio, barreira));
            confirmar(primeira.get(25, TimeUnit.SECONDS) ^ segunda.get(25, TimeUnit.SECONDS),
                    "uma única reserva simultânea do mesmo técnico");
        } finally {
            pool.shutdownNow();
            pool.awaitTermination(15, TimeUnit.SECONDS);
        }
    }

    private static boolean reservarConcorrente(String esquema, Dados dados, String chassi,
            OffsetDateTime inicio, CyclicBarrier barreira) throws Exception {
        try (Connection c = conectar(esquema)) {
            c.setAutoCommit(false);
            barreira.await(10, TimeUnit.SECONDS);
            try {
                OficinaService.agendar(c, horario(dados, chassi, dados.tecnico(), inicio));
                c.commit();
                return true;
            } catch (ErroNegocio e) {
                c.rollback();
                if (e.status != 409) throw e;
                return false;
            }
        }
    }

    private static long testarOrdemEEstoque(Connection c, long agendamento) throws Exception {
        long peca = id(OficinaService.cadastrarPeca(c, Map.of(
                "codigo", "FILTRO", "descricao", "Filtro", "valorUnitario", new BigDecimal("10.03"), "saldo", 3)));
        Map<String, Object> ordem = OficinaService.abrirOs(c, agendamento);
        long os = id(ordem);
        igual("ABERTA", ordem.get("estado"), "abertura");
        fila(c, "OFICINA_LEMBRETE", agendamento, "CANCELADA");
        esperarErro(409, () -> OficinaService.abrirOs(c, agendamento));
        Map<String, Object> itemPeca = Map.of("tipo", "PECA", "pecaId", peca, "quantidade", 2);
        esperarErro(409, () -> OficinaService.apontar(c, os, itemPeca));
        igual("EM_EXECUCAO", OficinaService.iniciarOs(c, os).get("estado"), "início");
        esperarErro(409, () -> OficinaService.concluirOs(c, os));

        var ponto = c.setSavepoint();
        decimal("20.06", OficinaService.apontar(c, os, itemPeca).get("subtotal"), "valor parcial da peça");
        numero(1, saldo(c, peca), "baixa do estoque");
        c.rollback(ponto);
        numero(3, saldo(c, peca), "rollback do estoque");
        decimal("0.00", OficinaService.consultarOs(c, os).get("subtotal"), "rollback do subtotal");
        igual(0, ((List<?>) OficinaService.consultarOs(c, os).get("itens")).size(), "rollback do item");

        OficinaService.apontar(c, os, itemPeca);
        esperarErro(409, () -> OficinaService.apontar(c, os, itemPeca));
        numero(1, saldo(c, peca), "saldo após recusa");
        Map<String, Object> atualizada = OficinaService.apontar(c, os, Map.of(
                "tipo", "SERVICO", "descricao", "Instalação", "valorUnitario", new BigDecimal("20.04"), "quantidade", 1));
        decimal("40.10", atualizada.get("subtotal"), "subtotal de peças e serviços");
        igual(2, ((List<?>) atualizada.get("itens")).size(), "itens válidos apontados");
        esperarErro(409, () -> OficinaService.faturar(c, os, OficinaService.aliquota("0.05")));
        igual("CONCLUIDA", OficinaService.concluirOs(c, os).get("estado"), "conclusão");
        esperarErro(409, () -> OficinaService.apontar(c, os, itemPeca));
        return os;
    }

    private static Object saldo(Connection c, long peca) throws SQLException {
        return Banco.um(c, "SELECT saldo FROM oficina_pecas WHERE id=?", peca).get("saldo");
    }

    private static long testarFaturamento(Connection c, long os) throws SQLException {
        Map<String, Object> fatura = OficinaService.faturar(c, os, OficinaService.aliquota("0.05"));
        decimal("40.10", fatura.get("subtotal"), "subtotal da fatura");
        decimal("2.01", fatura.get("imposto"), "arredondamento do imposto");
        decimal("42.11", fatura.get("total"), "total da fatura");
        igual("FATURADA", OficinaService.consultarOs(c, os).get("estado"), "faturamento");
        Map<String, Object> repetida = OficinaService.faturar(c, os, OficinaService.aliquota("0.10"));
        numero(id(fatura), repetida.get("id"), "idempotência da fatura");
        decimal("0.05", repetida.get("aliquota"), "preservação da alíquota original");
        fila(c, "OFICINA_FATURADA", id(fatura), "PENDENTE");
        return id(fatura);
    }

    private static void fila(Connection c, String evento, long referencia, String estado) throws SQLException {
        List<Map<String, Object>> linhas = Banco.lista(c, """
                SELECT canal, estado, tentativas FROM oficina_notificacoes
                WHERE evento=? AND referencia=? ORDER BY canal
                """, evento, Long.toString(referencia));
        igual(List.of("EMAIL", "SMS"), linhas.stream().map(linha -> linha.get("canal")).toList(), "canais de " + evento);
        for (Map<String, Object> linha : linhas) {
            igual(estado, linha.get("estado"), "estado da notificação " + evento);
            numero(0, linha.get("tentativas"), "tentativas na fila sem worker");
        }
    }

    private static void testarRelatorio(Connection c, Dados dados, long os, long fatura) throws Exception {
        Banco.executar(c, "UPDATE vendas SET data=? WHERE id=?", LocalDate.parse("2025-05-01"), dados.venda());
        Banco.executar(c, """
                INSERT INTO test_drives (cliente_id, veiculo_id, vendedor_id, data_hora, status)
                SELECT cliente_id, veiculo_id, vendedor_id, TIMESTAMP '2025-05-10 09:00:00', ?
                FROM propostas WHERE id=?
                """, TestDriveService.CONCLUIDO, dados.proposta());
        Banco.executar(c, """
                INSERT INTO test_drives (cliente_id, veiculo_id, vendedor_id, data_hora, status)
                SELECT cliente_id, veiculo_id, vendedor_id, TIMESTAMP '2025-07-01 00:00:00', 'Agendado'
                FROM propostas WHERE id=?
                """, dados.proposta());
        OffsetDateTime inicio = OffsetDateTime.parse("2025-05-01T03:00:00Z");
        Banco.executar(c, "UPDATE oficina_agendamentos SET criado_em=?", inicio);
        Banco.executar(c, "UPDATE oficina_os SET aberta_em=?, concluida_em=? WHERE id=?", inicio, inicio.plusHours(1), os);
        Banco.executar(c, "UPDATE oficina_faturas SET emitida_em=? WHERE id=?", inicio.plusHours(1), fatura);

        Map<String, Object> relatorio = DashboardService.relatorio(c, Map.of("inicio", "2025-05-01", "fim", "2025-07-01"));
        Map<?, ?> totais = (Map<?, ?>) relatorio.get("totais");
        numero(1, totais.get("vendas"), "vendas no período");
        decimal("165000.00", totais.get("receitaVendas"), "receita de vendas");
        numero(1, totais.get("testDrives"), "limite final exclusivo dos test drives");
        numero(1, totais.get("testDrivesConcluidos"), "test drives concluídos");
        numero(4, totais.get("agendamentosOficina"), "agendamentos históricos, incluindo cancelamento");
        numero(1, totais.get("osAbertas"), "OS abertas no período");
        numero(1, totais.get("osConcluidas"), "OS concluídas no período");
        numero(1, totais.get("faturas"), "faturas no período");
        decimal("2.01", totais.get("impostosOficina"), "impostos da oficina no painel");
        decimal("42.11", totais.get("receitaOficina"), "receita da oficina no painel");
        List<?> meses = (List<?>) relatorio.get("meses");
        igual(2, meses.size(), "série mensal completa");
        Map<?, ?> junho = (Map<?, ?>) meses.get(1);
        igual("2025-06", junho.get("mes"), "mês sem movimentação na série");
        numero(0, junho.get("vendas"), "vendas do mês vazio");
        List<?> modelos = (List<?>) relatorio.get("maisVendidos");
        igual("Toyota Corolla", ((Map<?, ?>) modelos.get(0)).get("modelo"), "ranking de modelos");
        esperarErro(400, () -> DashboardService.relatorio(c, Map.of("inicio", "2025-07-01", "fim", "2025-05-01")));
    }

    private static void esperarErro(int status, Operacao operacao) throws Exception {
        try {
            operacao.executar();
        } catch (ErroNegocio e) {
            igual(status, e.status, "status do erro de negócio");
            return;
        }
        throw new AssertionError("A operação deveria retornar o erro " + status);
    }

    private static long id(Map<String, Object> registro) {
        return Banco.longo(registro.get("id"));
    }

    private static void numero(long esperado, Object real, String caso) {
        igual(esperado, Banco.longo(real), caso);
    }

    private static void decimal(String esperado, Object real, String caso) {
        confirmar(real instanceof BigDecimal numero && numero.compareTo(new BigDecimal(esperado)) == 0,
                caso + " (esperado " + esperado + ", recebido " + real + ")");
    }

    private static void igual(Object esperado, Object real, String caso) {
        confirmar(esperado.equals(real), caso + " (esperado " + esperado + ", recebido " + real + ")");
    }

    private static void confirmar(boolean condicao, String caso) {
        if (!condicao) throw new AssertionError(caso);
    }
}
