import java.math.*;
import java.sql.*;
import java.util.*;

// Propostas comerciais e vendas.
class PropostaService {
    static final String AGUARDANDO = "Aguardando aprovação", APROVADA = "Aprovada", RECUSADA = "Recusada", VENDIDA = "Vendida";
    static final BigDecimal LIMITE_VENDEDOR = new BigDecimal("3");   // % de desconto que o vendedor pode dar sozinho
    static final BigDecimal DESCONTO_MAXIMO = new BigDecimal("10");  // % máximo permitido para qualquer perfil

    static List<Map<String, Object>> listar(Connection c) throws SQLException {
        return Banco.lista(c, "SELECT p.id, cl.nome AS cliente, ve.marca || ' ' || ve.modelo AS veiculo, "
            + "p.valor_tabela AS \"valorTabela\", p.desconto_pct AS \"descontoPct\", p.valor_final AS \"valorFinal\", "
            + "p.pagamento, p.status FROM propostas p JOIN clientes cl ON cl.id = p.cliente_id "
            + "JOIN veiculos ve ON ve.id = p.veiculo_id ORDER BY p.id DESC");
    }

    static Map<String, Object> criar(Connection c, Map<String, Object> b, Sessao s) throws SQLException {
        Long veiculoId = Json.longo(b, "veiculoId");
        if (veiculoId == null) throw ErroNegocio.invalido("Informe o veículo.");
        String pagamento = Json.txt(b, "pagamento");
        if (pagamento == null) throw ErroNegocio.invalido("Informe a condição de pagamento.");
        BigDecimal pct = Json.num(b, "descontoPct");
        if (pct == null) pct = BigDecimal.ZERO;
        if (pct.signum() < 0 || pct.compareTo(DESCONTO_MAXIMO) > 0)
            throw ErroNegocio.invalido("O desconto deve estar entre 0% e " + DESCONTO_MAXIMO + "%.");

        Map<String, Object> cliente = ClienteService.localizar(c, b);
        Map<String, Object> v = Banco.um(c, "SELECT preco, status FROM veiculos WHERE id=? FOR UPDATE", veiculoId);
        if (v == null) throw ErroNegocio.naoEncontrado("Veículo não encontrado.");

        // requisito RF06 aqui (pré-condição: veículo com status Disponível)
        if (!VeiculoService.DISPONIVEL.equals(v.get("status")))
            throw ErroNegocio.conflito("Veículo indisponível para proposta (status: " + v.get("status") + ").");

        // requisito RF06 aqui (cálculo automático do valor final com o desconto)
        BigDecimal tabela = (BigDecimal) v.get("preco");
        BigDecimal valorFinal = tabela.multiply(BigDecimal.valueOf(100).subtract(pct))
                                      .divide(BigDecimal.valueOf(100), 2, RoundingMode.HALF_UP);

        // requisito RF06 aqui (limite de alçada do vendedor e fluxo de aprovação gerencial)
        BigDecimal alcada = Sessao.VENDEDOR.equals(s.perfil) ? LIMITE_VENDEDOR : DESCONTO_MAXIMO;
        String status = pct.compareTo(alcada) > 0 ? AGUARDANDO : APROVADA;

        // O veículo fica reservado enquanto a proposta existe (diagrama de estado do veículo).
        VeiculoService.mudarStatus(c, veiculoId, VeiculoService.RESERVADO);

        Map<String, Object> p = new LinkedHashMap<>(Banco.um(c, "INSERT INTO propostas "
            + "(cliente_id, veiculo_id, vendedor_id, valor_tabela, desconto_pct, valor_final, pagamento, status) "
            + "VALUES (?,?,?,?,?,?,?,?) RETURNING id, valor_tabela AS \"valorTabela\", desconto_pct AS \"descontoPct\", "
            + "valor_final AS \"valorFinal\", pagamento, status",
            cliente.get("id"), veiculoId, s.id, tabela, pct, valorFinal, pagamento, status));
        p.put("alcadaDoPerfilPct", alcada);
        return p;
    }

    static Map<String, Object> decidir(Connection c, long id, Map<String, Object> b, Sessao s) throws SQLException {
        Map<String, Object> p = Banco.um(c, "SELECT veiculo_id AS \"veiculoId\", status FROM propostas WHERE id=? FOR UPDATE", id);
        if (p == null) throw ErroNegocio.naoEncontrado("Proposta não encontrada.");
        if (!(b.get("aprovar") instanceof Boolean aprovar)) throw ErroNegocio.invalido("Informe aprovar: true ou false.");

        // requisito RF06 aqui (aprovação gerencial de desconto acima da alçada)
        if (!AGUARDANDO.equals(p.get("status")))
            throw ErroNegocio.conflito("Esta proposta não está aguardando aprovação.");
        if (!aprovar) VeiculoService.mudarStatus(c, Banco.longo(p.get("veiculoId")), VeiculoService.DISPONIVEL);
        return Banco.um(c, "UPDATE propostas SET status=?, aprovado_por=? WHERE id=? RETURNING id, status",
            aprovar ? APROVADA : RECUSADA, s.id, id);
    }

    // Cliente aceitou: transforma a proposta aprovada em venda.
    static Map<String, Object> fecharVenda(Connection c, long id) throws SQLException {
        Map<String, Object> p = Banco.um(c, "SELECT veiculo_id AS \"veiculoId\", status FROM propostas WHERE id=? FOR UPDATE", id);
        if (p == null) throw ErroNegocio.naoEncontrado("Proposta não encontrada.");
        if (!APROVADA.equals(p.get("status")))
            throw ErroNegocio.conflito("Só propostas aprovadas podem virar venda (status atual: " + p.get("status") + ").");

        // requisito RF06 aqui (conversão em venda: veículo passa para Vendido)
        VeiculoService.mudarStatus(c, Banco.longo(p.get("veiculoId")), VeiculoService.VENDIDO);
        Map<String, Object> venda = Banco.um(c, "INSERT INTO vendas (proposta_id, data, valor, pagamento, cliente_id, veiculo_id) "
            + "SELECT id, CURRENT_DATE, valor_final, pagamento, cliente_id, veiculo_id FROM propostas WHERE id=? "
            + "RETURNING id, proposta_id AS \"propostaId\", data, valor", id);
        Banco.executar(c, "UPDATE propostas SET status=? WHERE id=?", VENDIDA, id);
        return venda;
    }

    // Dados da venda para a tela de contrato (o PDF é gerado pelo botão de impressão do front).
    static Map<String, Object> buscarVenda(Connection c, long id) throws SQLException {
        Map<String, Object> r = Banco.um(c, "SELECT v.id, v.data, v.valor, v.pagamento, cl.nome AS cnome, cl.cpf, cl.endereco, "
            + "ve.marca, ve.modelo, ve.ano, ve.chassi FROM vendas v JOIN clientes cl ON cl.id = v.cliente_id "
            + "JOIN veiculos ve ON ve.id = v.veiculo_id WHERE v.id=?", id);
        if (r == null) throw ErroNegocio.naoEncontrado("Venda não encontrada.");
        Map<String, Object> cliente = new LinkedHashMap<>();
        cliente.put("nome", r.get("cnome")); cliente.put("cpf", r.get("cpf")); cliente.put("endereco", r.get("endereco"));
        Map<String, Object> veiculo = new LinkedHashMap<>();
        for (String k : List.of("marca", "modelo", "ano", "chassi")) veiculo.put(k, r.get(k));
        Map<String, Object> out = new LinkedHashMap<>();
        for (String k : List.of("id", "data", "valor", "pagamento")) out.put(k, r.get(k));
        out.put("cliente", cliente); out.put("veiculo", veiculo);
        return out;
    }
}
