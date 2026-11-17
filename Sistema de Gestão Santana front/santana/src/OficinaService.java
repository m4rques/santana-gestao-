import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;

final class OficinaService {
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final DateTimeFormatter DATA_MENSAGEM = DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm").withZone(FUSO);
    private static final String AGENDA = """
        SELECT a.id, a.cliente_id AS "clienteId", cl.nome AS cliente, v.chassi, a.tecnico_id AS "tecnicoId",
               t.nome AS tecnico, a.inicio, a.fim, a.estado, a.criado_em AS "criadoEm", o.id AS "osId"
        FROM oficina_agendamentos a JOIN veiculos v ON v.id = a.veiculo_id
        JOIN clientes cl ON cl.id = a.cliente_id JOIN oficina_tecnicos t ON t.id = a.tecnico_id
        LEFT JOIN oficina_os o ON o.agendamento_id = a.id
        """;
    private static final String OS = """
        SELECT o.id, o.agendamento_id AS "agendamentoId", o.estado, o.subtotal,
               o.aberta_em AS "abertaEm", o.concluida_em AS "concluidaEm", v.chassi, cl.nome AS cliente
        FROM oficina_os o JOIN oficina_agendamentos a ON a.id = o.agendamento_id
        JOIN veiculos v ON v.id = a.veiculo_id JOIN clientes cl ON cl.id = a.cliente_id
        """;
    private static final String FATURA =
        "id, os_id AS \"osId\", subtotal, aliquota, imposto, total, emitida_em AS \"emitidaEm\"";
    private static final String PECA = "id, codigo, descricao, valor_unitario AS \"valorUnitario\", saldo";
    private static final String LEMBRETE = "OFICINA_LEMBRETE";
    private static final String CONFIRMACAO = "OFICINA_AGENDADO";

    private OficinaService() {}

    static List<Map<String, Object>> tecnicos(Connection c) throws SQLException {
        return Banco.lista(c, "SELECT id, nome FROM oficina_tecnicos ORDER BY nome");
    }

    static Map<String, Object> cadastrarTecnico(Connection c, Map<String, Object> b) throws SQLException {
        return Banco.um(c, "INSERT INTO oficina_tecnicos (nome) VALUES (?) RETURNING id, nome", texto(b, "nome", 120));
    }

    static List<Map<String, Object>> pecas(Connection c) throws SQLException {
        return Banco.lista(c, "SELECT " + PECA + " FROM oficina_pecas ORDER BY codigo");
    }

    static Map<String, Object> cadastrarPeca(Connection c, Map<String, Object> b) throws SQLException {
        return Banco.um(c, "INSERT INTO oficina_pecas (codigo, descricao, valor_unitario, saldo) VALUES (?,?,?,?) RETURNING " + PECA,
            texto(b, "codigo", 40), texto(b, "descricao", 120), preco(b), inteiro(b, "saldo", 0, Integer.MAX_VALUE));
    }

    static Map<String, Object> reporPeca(Connection c, long id, Map<String, Object> b) throws SQLException {
        Map<String, Object> peca = buscar(Banco.um(c, "SELECT saldo FROM oficina_pecas WHERE id=? FOR UPDATE", id), "Peça");
        int quantidade = quantidade(b);
        if (Banco.longo(peca.get("saldo")) + quantidade > Integer.MAX_VALUE)
            throw ErroNegocio.conflito("A reposição excede o saldo máximo.");
        return Banco.um(c, "UPDATE oficina_pecas SET saldo=saldo+? WHERE id=? RETURNING " + PECA, quantidade, id);
    }

    static List<Map<String, Object>> agenda(Connection c) throws SQLException {
        return Banco.lista(c, AGENDA + " ORDER BY a.inicio DESC, a.id DESC");
    }

    static Map<String, Object> agendar(Connection c, Map<String, Object> b) throws SQLException {
        String chassi = texto(b, "chassi", 30);
        long cliente = identificador(b, "clienteId"), tecnico = identificador(b, "tecnicoId");
        OffsetDateTime inicio = horario(b, "inicio"), fim = horario(b, "fim");
        if (!fim.isAfter(inicio)) throw ErroNegocio.invalido("O fim deve ser posterior ao início.");

        Map<String, Object> veiculo = buscar(Banco.um(c, "SELECT id FROM veiculos WHERE chassi=? FOR UPDATE", chassi), "Veículo");
        buscar(Banco.um(c, "SELECT id FROM oficina_tecnicos WHERE id=? FOR UPDATE", tecnico), "Técnico");
        buscar(Banco.um(c, "SELECT id FROM clientes WHERE id=?", cliente), "Cliente");
        long veiculoId = Banco.longo(veiculo.get("id"));
        Instant agora = Instant.now();
        if (!inicio.toInstant().isAfter(agora)) throw ErroNegocio.invalido("O início deve estar no futuro.");
        if (Banco.um(c, """
            SELECT id FROM oficina_agendamentos WHERE estado <> 'CANCELADO'
            AND (veiculo_id=? OR tecnico_id=?) AND inicio < ? AND fim > ? LIMIT 1
            """, veiculoId, tecnico, fim, inicio) != null)
            throw ErroNegocio.conflito("O veículo ou o técnico já tem um agendamento nesse intervalo.");

        long id = Banco.longo(Banco.um(c, """
            INSERT INTO oficina_agendamentos (veiculo_id, cliente_id, tecnico_id, inicio, fim)
            VALUES (?,?,?,?,?) RETURNING id
            """, veiculoId, cliente, tecnico, inicio, fim).get("id"));
        String quando = DATA_MENSAGEM.format(inicio.toInstant());
        NotificacaoService.enfileirar(c, cliente, CONFIRMACAO, Long.toString(id), "Agendamento confirmado",
            "Agendamento #" + id + " confirmado para " + quando + " (horário de Brasília).", agora);
        Instant lembrete = inicio.toInstant().minus(Duration.ofHours(24));
        if (lembrete.isAfter(agora)) {
            NotificacaoService.enfileirar(c, cliente, LEMBRETE, Long.toString(id), "Lembrete da oficina",
                "Seu atendimento #" + id + " é em " + quando + " (horário de Brasília).", lembrete);
        }
        return Banco.um(c, AGENDA + " WHERE a.id=?", id);
    }

    static Map<String, Object> cancelar(Connection c, long id) throws SQLException {
        Map<String, Object> a = travarAgenda(c, id);
        exigirEstado(a, "AGENDADO", "Só agendamentos ativos podem ser cancelados.");
        Banco.executar(c, "UPDATE oficina_agendamentos SET estado='CANCELADO' WHERE id=?", id);
        String referencia = Long.toString(id);
        NotificacaoService.cancelarPendente(c, LEMBRETE, referencia);
        NotificacaoService.cancelarPendente(c, CONFIRMACAO, referencia);
        NotificacaoService.enfileirar(c, Banco.longo(a.get("cliente_id")), "OFICINA_CANCELADO", referencia,
            "Agendamento cancelado", "O agendamento #" + id + " foi cancelado.", Instant.now());
        return Banco.um(c, AGENDA + " WHERE a.id=?", id);
    }

    static Map<String, Object> abrirOs(Connection c, long agendamento) throws SQLException {
        exigirEstado(travarAgenda(c, agendamento), "AGENDADO", "Só agendamentos ativos podem virar OS.");
        long id = Banco.longo(Banco.um(c, "INSERT INTO oficina_os (agendamento_id) VALUES (?) RETURNING id", agendamento).get("id"));
        Banco.executar(c, "UPDATE oficina_agendamentos SET estado='CONVERTIDO' WHERE id=?", agendamento);
        NotificacaoService.cancelarPendente(c, LEMBRETE, Long.toString(agendamento));
        return consultarOs(c, id);
    }

    static List<Map<String, Object>> ordens(Connection c) throws SQLException {
        return Banco.lista(c, OS + " ORDER BY o.id DESC");
    }

    static Map<String, Object> consultarOs(Connection c, long id) throws SQLException {
        Map<String, Object> os = buscar(Banco.um(c, OS + " WHERE o.id=? FOR SHARE OF o", id), "OS");
        os.put("itens", Banco.lista(c, """
            SELECT id, tipo, peca_id AS "pecaId", descricao, quantidade, valor_unitario AS "valorUnitario",
                   quantidade * valor_unitario AS total FROM oficina_itens WHERE os_id=? ORDER BY id
            """, id));
        os.put("fatura", Banco.um(c, "SELECT " + FATURA + " FROM oficina_faturas WHERE os_id=?", id));
        return os;
    }

    static Map<String, Object> iniciarOs(Connection c, long id) throws SQLException {
        exigirEstado(travarOs(c, id), "ABERTA", "Só uma OS aberta pode ser iniciada.");
        Banco.executar(c, "UPDATE oficina_os SET estado='EM_EXECUCAO' WHERE id=?", id);
        return consultarOs(c, id);
    }

    static Map<String, Object> apontar(Connection c, long id, Map<String, Object> b) throws SQLException {
        exigirEstado(travarOs(c, id), "EM_EXECUCAO", "Só uma OS em execução pode receber itens.");
        String tipo = texto(b, "tipo", 7);
        int quantidade = quantidade(b);
        Long pecaId = null;
        BigDecimal unitario;
        String descricao;
        switch (tipo) {
            case "PECA" -> {
                pecaId = identificador(b, "pecaId");
                buscar(Banco.um(c, "SELECT id FROM oficina_pecas WHERE id=?", pecaId), "Peça");
                Map<String, Object> peca = Banco.um(c, """
                    UPDATE oficina_pecas SET saldo=saldo-? WHERE id=? AND saldo>=? RETURNING descricao, valor_unitario
                    """, quantidade, pecaId, quantidade);
                if (peca == null) throw ErroNegocio.conflito("Saldo insuficiente para a peça.");
                descricao = (String) peca.get("descricao");
                unitario = (BigDecimal) peca.get("valor_unitario");
            }
            case "SERVICO" -> {
                descricao = texto(b, "descricao", 120);
                unitario = preco(b);
            }
            default -> throw ErroNegocio.invalido("O tipo deve ser PECA ou SERVICO.");
        }
        Banco.executar(c, """
            INSERT INTO oficina_itens (os_id, tipo, peca_id, descricao, quantidade, valor_unitario) VALUES (?,?,?,?,?,?)
            """, id, tipo, pecaId, descricao, quantidade, unitario);
        Banco.executar(c, "UPDATE oficina_os SET subtotal=subtotal+? WHERE id=?", unitario.multiply(BigDecimal.valueOf(quantidade)), id);
        return consultarOs(c, id);
    }

    static Map<String, Object> concluirOs(Connection c, long id) throws SQLException {
        Map<String, Object> os = travarOs(c, id);
        exigirEstado(os, "EM_EXECUCAO", "Só uma OS em execução pode ser concluída.");
        if (((BigDecimal) os.get("subtotal")).signum() == 0)
            throw ErroNegocio.conflito("Aponte pelo menos um item antes de concluir a OS.");
        Banco.executar(c, "UPDATE oficina_os SET estado='CONCLUIDA', concluida_em=clock_timestamp() WHERE id=?", id);
        return consultarOs(c, id);
    }

    static Map<String, Object> faturar(Connection c, long id, BigDecimal aliquota) throws SQLException {
        Map<String, Object> os = travarOs(c, id);
        if ("FATURADA".equals(os.get("estado"))) {
            return Banco.um(c, "SELECT " + FATURA + " FROM oficina_faturas WHERE os_id=?", id);
        }
        exigirEstado(os, "CONCLUIDA", "Só uma OS concluída pode ser faturada.");
        BigDecimal subtotal = (BigDecimal) os.get("subtotal");
        BigDecimal imposto = subtotal.multiply(aliquota).setScale(2, RoundingMode.HALF_UP);
        BigDecimal total = subtotal.add(imposto);
        Map<String, Object> fatura = Banco.um(c, """
            INSERT INTO oficina_faturas (os_id, subtotal, aliquota, imposto, total)
            VALUES (?,?,?,?,?) RETURNING
            """ + FATURA, id, subtotal, aliquota, imposto, total);
        Banco.executar(c, "UPDATE oficina_os SET estado='FATURADA' WHERE id=?", id);
        long cliente = Banco.longo(Banco.um(c, "SELECT cliente_id FROM oficina_agendamentos WHERE id=?", os.get("agendamento_id")).get("cliente_id"));
        NotificacaoService.enfileirar(c, cliente, "OFICINA_FATURADA", fatura.get("id").toString(), "Fatura da oficina",
            "A ordem de serviço #" + id + " foi faturada. Total R$ " + total.toPlainString().replace('.', ',') + ".", Instant.now());
        return fatura;
    }

    static BigDecimal aliquota(String valor) {
        try {
            BigDecimal numero = new BigDecimal(valor);
            if (numero.signum() < 0 || numero.compareTo(BigDecimal.ONE) > 0 || numero.stripTrailingZeros().scale() > 6)
                throw new NumberFormatException();
            return numero.setScale(6);
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("OFICINA_IMPOSTO deve ser decimal de 0 a 1, com até 6 casas decimais.", e);
        }
    }

    private static Map<String, Object> travarAgenda(Connection c, long id) throws SQLException {
        return buscar(Banco.um(c, "SELECT estado, cliente_id FROM oficina_agendamentos WHERE id=? FOR UPDATE", id), "Agendamento");
    }

    private static Map<String, Object> travarOs(Connection c, long id) throws SQLException {
        return buscar(Banco.um(c, "SELECT estado, subtotal, agendamento_id FROM oficina_os WHERE id=? FOR UPDATE", id), "OS");
    }

    private static Map<String, Object> buscar(Map<String, Object> linha, String entidade) {
        if (linha == null) throw ErroNegocio.naoEncontrado(entidade + " não encontrado(a).");
        return linha;
    }

    private static void exigirEstado(Map<String, Object> linha, String estado, String mensagem) {
        if (!estado.equals(linha.get("estado"))) throw ErroNegocio.conflito(mensagem);
    }

    private static String texto(Map<String, Object> b, String campo, int maximo) {
        String valor = Json.txt(b, campo);
        if (valor == null || valor.length() > maximo || valor.indexOf('\0') >= 0)
            throw ErroNegocio.invalido("Informe " + campo + " com até " + maximo + " caracteres.");
        return valor;
    }

    private static long identificador(Map<String, Object> b, String campo) {
        return identificador(b, campo, 1);
    }

    private static int quantidade(Map<String, Object> b) {
        return inteiro(b, "quantidade", 1, 1_000_000);
    }

    private static int inteiro(Map<String, Object> b, String campo, int minimo, int maximo) {
        long valor = identificador(b, campo, minimo);
        if (valor > maximo) throw ErroNegocio.invalido(campo + " excede o máximo de " + maximo + ".");
        return (int) valor;
    }

    private static long identificador(Map<String, Object> b, String campo, int minimo) {
        BigDecimal numero = Json.num(b, campo);
        if (numero != null) {
            try {
                long valor = numero.longValueExact();
                if (valor >= minimo) return valor;
            } catch (ArithmeticException e) {
            }
        }
        throw ErroNegocio.invalido("Informe " + campo + " como um inteiro maior ou igual a " + minimo + ".");
    }

    private static BigDecimal preco(Map<String, Object> b) {
        BigDecimal valor = Json.num(b, "valorUnitario");
        if (valor == null || valor.signum() <= 0 || valor.compareTo(new BigDecimal("9999999999.99")) > 0
                || valor.stripTrailingZeros().scale() > 2)
            throw ErroNegocio.invalido("Informe valorUnitario positivo, com até 10 dígitos inteiros e 2 casas decimais.");
        return valor.setScale(2);
    }

    static OffsetDateTime horario(Map<String, Object> b, String campo) {
        String texto = Json.txt(b, campo);
        if (texto != null) {
            try {
                return OffsetDateTime.parse(texto).truncatedTo(ChronoUnit.MICROS);
            } catch (DateTimeParseException e) {
            }
        }
        throw ErroNegocio.invalido("Informe " + campo + " como data e hora ISO 8601 com offset.");
    }
}
