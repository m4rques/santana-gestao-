import br.com.oficina.ContratoPdf;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.text.NumberFormat;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.Locale;
import java.util.Map;

import static java.util.Map.entry;

final class ContratoService {
    private static final Locale PT_BR = Locale.forLanguageTag("pt-BR");
    private static final DateTimeFormatter DATA_BR = DateTimeFormatter.ofPattern("dd/MM/uuuu", PT_BR);

    private ContratoService() {
    }

    static byte[] gerar(Connection c, long vendaId, Path template) throws SQLException, IOException {
        Map<String, Object> venda = PropostaService.buscarVenda(c, vendaId);
        Map<?, ?> cliente = (Map<?, ?>) venda.get("cliente");
        Map<?, ?> veiculo = (Map<?, ?>) venda.get("veiculo");
        String endereco = (String) cliente.get("endereco");
        String data = LocalDate.parse(venda.get("data").toString()).format(DATA_BR);
        String valor = NumberFormat.getCurrencyInstance(PT_BR).format(venda.get("valor"));

        return ContratoPdf.gerar(template, Map.ofEntries(
                entry("vendaId", venda.get("id").toString()),
                entry("propostaId", venda.get("propostaId").toString()),
                entry("vendedor", venda.get("vendedor").toString()),
                entry("comprador", cliente.get("nome").toString()),
                entry("documento", cliente.get("cpf").toString()),
                entry("endereco", endereco == null || endereco.isBlank() ? "Não informado" : endereco),
                entry("veiculo", veiculo.get("marca") + " " + veiculo.get("modelo")),
                entry("ano", veiculo.get("ano").toString()),
                entry("chassi", veiculo.get("chassi").toString()),
                entry("valor", valor),
                entry("pagamento", venda.get("pagamento").toString()),
                entry("data", data)));
    }
}
