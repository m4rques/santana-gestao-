import java.math.BigDecimal;
import java.sql.*;
import java.time.Year;
import java.util.*;

// Estoque de veículos.
class VeiculoService {
    static final String DISPONIVEL = "Disponível", RESERVADO = "Reservado", EM_TESTDRIVE = "Em Test-Drive",
                        EM_MANUTENCAO = "Em manutenção", VENDIDO = "Vendido";

    // Transições permitidas (diagrama de estado do ciclo de vida do veículo).
    static final Map<String, Set<String>> TRANSICOES = Map.of(
        DISPONIVEL,    Set.of(RESERVADO, EM_TESTDRIVE, EM_MANUTENCAO),
        RESERVADO,     Set.of(DISPONIVEL, VENDIDO),
        EM_TESTDRIVE,  Set.of(DISPONIVEL, EM_MANUTENCAO),
        EM_MANUTENCAO, Set.of(DISPONIVEL),
        VENDIDO,       Set.of());

    private static final String CAMPOS = "id, chassi, marca, modelo, ano, tipo, km, preco, status";

    static List<Map<String, Object>> listar(Connection c, Map<String, String> q) throws SQLException {
        StringBuilder sql = new StringBuilder("SELECT " + CAMPOS + " FROM veiculos WHERE 1=1");
        List<Object> p = new ArrayList<>();
        if (!vazio(q.get("marca")))    { sql.append(" AND marca ILIKE ?");  p.add("%" + q.get("marca").trim() + "%"); }
        if (!vazio(q.get("modelo")))   { sql.append(" AND modelo ILIKE ?"); p.add("%" + q.get("modelo").trim() + "%"); }
        if (!vazio(q.get("precoMax"))) { sql.append(" AND preco <= ?");     p.add(preco(q.get("precoMax"))); }
        if (!vazio(q.get("status")))   { sql.append(" AND status = ?");     p.add(q.get("status")); }
        sql.append(" ORDER BY marca, modelo");
        return Banco.lista(c, sql.toString(), p.toArray());
    }

    static Map<String, Object> buscar(Connection c, long id) throws SQLException {
        Map<String, Object> v = Banco.um(c, "SELECT " + CAMPOS + " FROM veiculos WHERE id=?", id);
        if (v == null) throw ErroNegocio.naoEncontrado("Veículo não encontrado.");
        return v;
    }

    static Map<String, Object> criar(Connection c, Map<String, Object> b) throws SQLException {
        String chassi = Json.txt(b, "chassi"), marca = Json.txt(b, "marca"),
               modelo = Json.txt(b, "modelo"), tipo = Json.txt(b, "tipo");
        Long ano = Json.longo(b, "ano"), km = Json.longo(b, "km");
        BigDecimal preco = Json.num(b, "preco");
        if (km == null) km = 0L;

        // requisito RF01 aqui (validação do cadastro de novos/seminovos)
        if (chassi == null || marca == null || modelo == null || ano == null || preco == null)
            throw ErroNegocio.invalido("Informe chassi, marca, modelo, ano e preço.");
        if (!"Novo".equals(tipo) && !"Seminovo".equals(tipo))
            throw ErroNegocio.invalido("O tipo deve ser Novo ou Seminovo.");
        if (ano < 1980 || ano > Year.now().getValue() + 1)
            throw ErroNegocio.invalido("Ano inválido.");
        if (preco.signum() <= 0) throw ErroNegocio.invalido("O preço deve ser maior que zero.");
        if (km < 0) throw ErroNegocio.invalido("A quilometragem não pode ser negativa.");
        if ("Novo".equals(tipo) && km > 0)
            throw ErroNegocio.invalido("Veículo novo deve ter quilometragem zero.");
        if (Banco.um(c, "SELECT 1 FROM veiculos WHERE chassi=?", chassi) != null)
            throw ErroNegocio.conflito("Já existe um veículo com este chassi.");

        // requisito RF01 aqui (todo veículo cadastrado entra com status Disponível)
        return Banco.um(c, "INSERT INTO veiculos (chassi, marca, modelo, ano, tipo, km, preco, status) "
            + "VALUES (?,?,?,?,?,?,?,?) RETURNING " + CAMPOS,
            chassi, marca, modelo, ano.intValue(), tipo, km.intValue(), preco, DISPONIVEL);
    }

    // Também é chamado pelos fluxos de test-drive (RF05) e de venda (RF06), dentro da mesma transação.
    static void mudarStatus(Connection c, long id, String novo) throws SQLException {
        if (novo == null || !TRANSICOES.containsKey(novo)) throw ErroNegocio.invalido("Status inválido.");
        Map<String, Object> v = Banco.um(c, "SELECT status FROM veiculos WHERE id=? FOR UPDATE", id);
        if (v == null) throw ErroNegocio.naoEncontrado("Veículo não encontrado.");
        String atual = (String) v.get("status");

        // requisito RF01 aqui (alteração de status só pelas transições do ciclo de vida)
        if (!TRANSICOES.get(atual).contains(novo))
            throw ErroNegocio.conflito("Transição de status inválida: " + atual + " -> " + novo + ".");
        Banco.executar(c, "UPDATE veiculos SET status=? WHERE id=?", novo, id);
    }

    private static boolean vazio(String s) { return s == null || s.isBlank(); }

    private static BigDecimal preco(String s) {
        try { return new BigDecimal(s.trim()); }
        catch (NumberFormatException e) { throw ErroNegocio.invalido("Preço máximo inválido."); }
    }
}
