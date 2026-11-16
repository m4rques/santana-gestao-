import java.sql.*;
import java.util.*;

// Cadastro de clientes (CRM).
class ClienteService {
    private static final String CAMPOS = "id, codigo, nome, cpf, telefone, email, endereco, cnh_status AS \"cnhStatus\"";

    static List<Map<String, Object>> listar(Connection c) throws SQLException {
        return Banco.lista(c, "SELECT " + CAMPOS + " FROM clientes ORDER BY nome");
    }

    static Map<String, Object> criar(Connection c, Map<String, Object> b) throws SQLException {
        String nome = Json.txt(b, "nome");
        String doc = Json.txt(b, "cpf") == null ? "" : Json.txt(b, "cpf").replaceAll("\\D", "");
        if (nome == null) throw ErroNegocio.invalido("Informe o nome completo.");
        if (!docValido(doc)) throw ErroNegocio.invalido("CPF ou CNPJ inválido.");
        if (!Boolean.TRUE.equals(b.get("consentimento")))
            throw ErroNegocio.invalido("O consentimento LGPD é obrigatório.");

        // requisito RF03 aqui (duplicidade de CPF/CNPJ)
        if (Banco.um(c, "SELECT 1 FROM clientes WHERE cpf=?", doc) != null)
            throw ErroNegocio.conflito("Cliente já cadastrado com este CPF/CNPJ.");

        // requisito RF03 aqui (geração do código único do cliente)
        long n = Banco.longo(Banco.um(c, "SELECT nextval('cliente_codigo_seq') AS n").get("n"));
        String codigo = String.format("CLI-%04d", n);

        String cnh = Json.txt(b, "cnh");
        return Banco.um(c, "INSERT INTO clientes (codigo, nome, cpf, telefone, email, endereco, cnh_arquivo, cnh_status, consentimento) "
            + "VALUES (?,?,?,?,?,?,?,?,TRUE) RETURNING " + CAMPOS,
            codigo, nome, doc, Json.txt(b, "telefone"), Json.txt(b, "email"), Json.txt(b, "endereco"),
            cnh, validarCnhSimulada(cnh));
    }

    // Localiza o cliente por clienteId ou clienteCodigo. Usado por test-drive (RF04) e proposta (RF06).
    static Map<String, Object> localizar(Connection c, Map<String, Object> b) throws SQLException {
        Long id = Json.longo(b, "clienteId");
        String cod = Json.txt(b, "clienteCodigo");
        Map<String, Object> cli;
        if (id != null) cli = Banco.um(c, "SELECT id, nome, cnh_status FROM clientes WHERE id=?", id);
        else if (cod != null) cli = Banco.um(c, "SELECT id, nome, cnh_status FROM clientes WHERE codigo=?", cod);
        else throw ErroNegocio.invalido("Informe o cliente (clienteId ou clienteCodigo).");
        if (cli == null) throw ErroNegocio.naoEncontrado("Cliente não encontrado.");
        return cli;
    }

    // SIMULADO: no sistema real seria uma consulta à API do Detran.
    // Regra de demonstração: CNH anexada = "Válida"; sem anexo = "Não informada".
    static String validarCnhSimulada(String arquivo) {
        return arquivo == null ? "Não informada" : "Válida";
    }

    // CPF (dígitos verificadores) ou CNPJ (14 dígitos, sem todos iguais).
    static boolean docValido(String d) {
        if (d.matches("(\\d)\\1+")) return false;
        if (d.length() == 14) return true;
        if (d.length() != 11) return false;
        for (int t = 9; t < 11; t++) {
            int soma = 0;
            for (int i = 0; i < t; i++) soma += (d.charAt(i) - '0') * (t + 1 - i);
            if ((soma * 10 % 11) % 10 != d.charAt(t) - '0') return false;
        }
        return true;
    }
}
