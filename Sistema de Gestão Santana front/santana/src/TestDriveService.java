import java.sql.*;
import java.time.*;
import java.util.*;

// Agenda de test-drives.
class TestDriveService {
    static final String AGENDADO = "Agendado", EM_ANDAMENTO = "Em andamento", CONCLUIDO = "Concluído", CANCELADO = "Cancelado";
    static final int DURACAO_MIN = 60;

    // Transições permitidas do test-drive.
    static final Map<String, Set<String>> TRANSICOES = Map.of(
        AGENDADO,     Set.of(EM_ANDAMENTO, CANCELADO),
        EM_ANDAMENTO, Set.of(CONCLUIDO),
        CONCLUIDO,    Set.of(),
        CANCELADO,    Set.of());

    static List<Map<String, Object>> listar(Connection c) throws SQLException {
        return Banco.lista(c, "SELECT t.id, cl.nome AS cliente, ve.marca || ' ' || ve.modelo AS veiculo, "
            + "t.data_hora AS \"dataHora\", t.status, t.feedback FROM test_drives t "
            + "JOIN clientes cl ON cl.id = t.cliente_id JOIN veiculos ve ON ve.id = t.veiculo_id ORDER BY t.data_hora");
    }

    static Map<String, Object> agendar(Connection c, Map<String, Object> b, long vendedorId) throws SQLException {
        Long veiculoId = Json.longo(b, "veiculoId");
        if (veiculoId == null) throw ErroNegocio.invalido("Informe o veículo.");
        LocalDateTime inicio;
        try { inicio = LocalDateTime.parse(String.valueOf(Json.txt(b, "dataHora"))); }
        catch (DateTimeException e) { throw ErroNegocio.invalido("Data e hora inválidas. Use o formato 2026-11-20T10:00."); }
        if (!inicio.isAfter(LocalDateTime.now())) throw ErroNegocio.invalido("O horário deve estar no futuro.");
        if (inicio.toLocalTime().isBefore(LocalTime.of(8, 0)) || inicio.toLocalTime().isAfter(LocalTime.of(17, 0)))
            throw ErroNegocio.invalido("Test-drives podem começar entre 08:00 e 17:00.");

        Map<String, Object> cliente = ClienteService.localizar(c, b);
        // Trava a linha do veículo para dois agendamentos simultâneos não passarem juntos.
        Map<String, Object> veiculo = Banco.um(c, "SELECT status FROM veiculos WHERE id=? FOR UPDATE", veiculoId);
        if (veiculo == null) throw ErroNegocio.naoEncontrado("Veículo não encontrado.");

        // requisito RF04 aqui (elegibilidade: CNH válida)
        if (!"Válida".equals(cliente.get("cnh_status")))
            throw ErroNegocio.conflito("Cliente sem CNH válida. Anexe a CNH no cadastro para liberar o test-drive.");

        // requisito RF04 aqui (elegibilidade: status do veículo)
        if (!VeiculoService.DISPONIVEL.equals(veiculo.get("status")))
            throw ErroNegocio.conflito("Veículo indisponível para test-drive (status: " + veiculo.get("status") + ").");

        // requisito RF04 aqui (bloqueio de horário na agenda do veículo e do cliente)
        Map<String, Object> choque = Banco.um(c, "SELECT 1 FROM test_drives WHERE status IN (?, ?) "
            + "AND (veiculo_id = ? OR cliente_id = ?) AND data_hora > ? AND data_hora < ?",
            AGENDADO, EM_ANDAMENTO, veiculoId, cliente.get("id"),
            inicio.minusMinutes(DURACAO_MIN), inicio.plusMinutes(DURACAO_MIN));
        if (choque != null) throw ErroNegocio.conflito("Horário indisponível: já existe test-drive deste veículo ou cliente nesse período.");

        return Banco.um(c, "INSERT INTO test_drives (cliente_id, veiculo_id, vendedor_id, data_hora, status) VALUES (?,?,?,?,?) "
            + "RETURNING id, cliente_id AS \"clienteId\", veiculo_id AS \"veiculoId\", data_hora AS \"dataHora\", status",
            cliente.get("id"), veiculoId, vendedorId, inicio, AGENDADO);
    }

    static Map<String, Object> mudarStatus(Connection c, long id, Map<String, Object> b) throws SQLException {
        String novo = Json.txt(b, "status");
        Map<String, Object> td = Banco.um(c, "SELECT veiculo_id AS \"veiculoId\", status FROM test_drives WHERE id=? FOR UPDATE", id);
        if (td == null) throw ErroNegocio.naoEncontrado("Test-drive não encontrado.");
        if (novo == null || !TRANSICOES.containsKey(novo)) throw ErroNegocio.invalido("Status inválido.");
        String atual = (String) td.get("status");
        long veiculoId = Banco.longo(td.get("veiculoId"));

        // requisito RF05 aqui (transição Agendado -> Em andamento -> Concluído)
        if (!TRANSICOES.get(atual).contains(novo))
            throw ErroNegocio.conflito("Transição inválida: " + atual + " -> " + novo + ".");

        String feedback = null;
        if (EM_ANDAMENTO.equals(novo)) {
            // requisito RF05 aqui (entrega da chave: veículo passa para Em Test-Drive)
            VeiculoService.mudarStatus(c, veiculoId, VeiculoService.EM_TESTDRIVE);
        } else if (CONCLUIDO.equals(novo)) {
            // requisito RF05 aqui (retorno do veículo: exige feedback do cliente)
            feedback = Json.txt(b, "feedback");
            if (feedback == null) throw ErroNegocio.invalido("Informe o feedback do cliente para concluir.");
            VeiculoService.mudarStatus(c, veiculoId, VeiculoService.DISPONIVEL);
        }
        return Banco.um(c, "UPDATE test_drives SET status=?, feedback=COALESCE(?, feedback) WHERE id=? "
            + "RETURNING id, veiculo_id AS \"veiculoId\", data_hora AS \"dataHora\", status, feedback", novo, feedback, id);
    }
}
