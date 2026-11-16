import com.sun.net.httpserver.*;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.util.*;
import java.util.concurrent.*;

// Servidor: entrega a pasta web/ e a API em /api. Rode esta classe pelo botão Run do VS Code.
public class Main {
    record Resp(int status, Object corpo) {}

    static final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
    static final long INATIVIDADE_MS = 30 * 60 * 1000; // sessão cai após 30 min sem uso

    public static void main(String[] args) throws Exception {
        try (Connection c = Banco.abrir()) {
            System.out.println("Banco de dados conectado.");
        } catch (SQLException e) {
            System.out.println("ATENÇÃO: não foi possível conectar ao banco: " + e.getMessage());
        }
        HttpServer srv = HttpServer.create(new InetSocketAddress(8080), 0);
        srv.createContext("/", Main::tratar);
        srv.setExecutor(Executors.newFixedThreadPool(8));
        srv.start();
        System.out.println("Servidor em http://localhost:8080");
    }

    static void tratar(HttpExchange ex) throws IOException {
        try {
            String p = ex.getRequestURI().getPath();
            if (p.startsWith("/api/")) api(ex, p.substring(5));
            else estatico(ex, p);
        } finally {
            ex.close();
        }
    }

    // ---------- API ----------
    static void api(HttpExchange ex, String caminho) throws IOException {
        try {
            Resp r = rotear(ex, caminho.split("/"));
            enviar(ex, r.status(), r.corpo());
        } catch (ErroNegocio e) {
            enviar(ex, e.status, Map.of("erro", e.getMessage()));
        } catch (SQLException e) {
            if ("23505".equals(e.getSQLState())) enviar(ex, 409, Map.of("erro", "Registro duplicado."));
            else { e.printStackTrace(); enviar(ex, 500, Map.of("erro", "Erro no banco de dados.")); }
        } catch (Exception e) {
            e.printStackTrace();
            enviar(ex, 500, Map.of("erro", "Erro interno do servidor."));
        }
    }

    static Resp rotear(HttpExchange ex, String[] seg) throws Exception {
        String m = ex.getRequestMethod();
        Map<String, Object> corpo = (m.equals("POST") || m.equals("PUT"))
            ? Json.objeto(new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)) : new HashMap<>();

        if (seg[0].equals("login") && m.equals("POST")) return new Resp(200, login(corpo));
        Sessao s = autenticar(ex);

        try (Connection c = Banco.abrir()) {
            c.setAutoCommit(false); // cada requisição é uma transação: ou grava tudo ou nada
            try {
                Resp r = despachar(c, m, seg, corpo, query(ex.getRequestURI().getRawQuery()), s);
                c.commit();
                return r;
            } catch (Exception e) {
                c.rollback();
                throw e;
            }
        }
    }

    static Resp despachar(Connection c, String m, String[] seg, Map<String, Object> b, Map<String, String> q, Sessao s) throws SQLException {
        boolean get = m.equals("GET"), post = m.equals("POST"), put = m.equals("PUT");
        int n = seg.length;

        switch (seg[0]) {
            case "veiculos" -> {
                if (n == 1 && get) return new Resp(200, VeiculoService.listar(c, q));
                if (n == 1 && post) {
                    exigir(s, Sessao.ADMIN, Sessao.GERENTE); // RF01: só Administrador e Gerente de Vendas
                    return new Resp(201, VeiculoService.criar(c, b));
                }
                if (n == 3 && put && seg[2].equals("status")) {
                    exigir(s, Sessao.ADMIN, Sessao.GERENTE);
                    VeiculoService.mudarStatus(c, id(seg[1]), Json.txt(b, "status"));
                    return new Resp(200, VeiculoService.buscar(c, id(seg[1])));
                }
            }
            case "clientes" -> {
                if (n == 1 && get) return new Resp(200, ClienteService.listar(c));
                if (n == 1 && post) {
                    exigir(s, Sessao.VENDEDOR, Sessao.RECEPCIONISTA, Sessao.GERENTE, Sessao.ADMIN);
                    return new Resp(201, ClienteService.criar(c, b));
                }
            }
            case "testdrives" -> {
                if (n == 1 && get) return new Resp(200, TestDriveService.listar(c));
                if (n == 1 && post) {
                    exigir(s, Sessao.VENDEDOR, Sessao.RECEPCIONISTA, Sessao.GERENTE, Sessao.ADMIN);
                    return new Resp(201, TestDriveService.agendar(c, b, s.id));
                }
                if (n == 3 && put && seg[2].equals("status")) {
                    exigir(s, Sessao.VENDEDOR, Sessao.GERENTE, Sessao.ADMIN); // RF05: ator Vendedor
                    return new Resp(200, TestDriveService.mudarStatus(c, id(seg[1]), b));
                }
            }
            case "propostas" -> {
                if (n == 1 && get) return new Resp(200, PropostaService.listar(c));
                if (n == 1 && post) {
                    exigir(s, Sessao.VENDEDOR, Sessao.GERENTE, Sessao.ADMIN);
                    return new Resp(201, PropostaService.criar(c, b, s));
                }
                if (n == 3 && put && seg[2].equals("decisao")) {
                    exigir(s, Sessao.GERENTE, Sessao.ADMIN); // só o gerente aprova desconto acima da alçada
                    return new Resp(200, PropostaService.decidir(c, id(seg[1]), b, s));
                }
                if (n == 3 && post && seg[2].equals("venda")) {
                    exigir(s, Sessao.VENDEDOR, Sessao.GERENTE, Sessao.ADMIN);
                    return new Resp(201, PropostaService.fecharVenda(c, id(seg[1])));
                }
            }
            case "vendas" -> {
                if (n == 2 && get) return new Resp(200, PropostaService.buscarVenda(c, id(seg[1])));
            }
            default -> { }
        }
        throw ErroNegocio.naoEncontrado("Rota não encontrada.");
    }

    // ---------- login e sessão ----------
    static Map<String, Object> login(Map<String, Object> b) throws Exception {
        String email = Json.txt(b, "email"), senha = Json.txt(b, "senha");
        if (email == null || senha == null) throw ErroNegocio.invalido("Informe e-mail e senha.");
        try (Connection c = Banco.abrir()) {
            Map<String, Object> u = Banco.um(c, "SELECT id, nome, perfil FROM usuarios WHERE email=? AND senha_hash=?", email, sha256(senha));
            if (u == null) throw ErroNegocio.naoAutorizado("E-mail ou senha incorretos.");
            String token = UUID.randomUUID().toString();
            sessoes.put(token, new Sessao(Banco.longo(u.get("id")), (String) u.get("nome"), (String) u.get("perfil")));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("token", token); r.put("nome", u.get("nome")); r.put("perfil", u.get("perfil"));
            return r;
        }
    }

    static Sessao autenticar(HttpExchange ex) {
        String h = ex.getRequestHeaders().getFirst("Authorization");
        String token = (h != null && h.startsWith("Bearer ")) ? h.substring(7) : "";
        Sessao s = sessoes.get(token);
        long agora = System.currentTimeMillis();
        if (s == null || agora - s.ultimoAcesso > INATIVIDADE_MS) {
            sessoes.remove(token);
            throw ErroNegocio.naoAutorizado("Sessão expirada ou inválida. Entre novamente.");
        }
        s.ultimoAcesso = agora;
        return s;
    }

    static void exigir(Sessao s, String... perfis) {
        if (!Arrays.asList(perfis).contains(s.perfil))
            throw ErroNegocio.proibido("Seu perfil (" + s.perfil + ") não tem permissão para esta ação.");
    }

    static String sha256(String t) throws Exception {
        return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(t.getBytes(StandardCharsets.UTF_8)));
    }

    // ---------- utilitários ----------
    static long id(String s) {
        try { return Long.parseLong(s); }
        catch (NumberFormatException e) { throw ErroNegocio.invalido("Identificador inválido."); }
    }

    static Map<String, String> query(String raw) {
        Map<String, String> m = new HashMap<>();
        if (raw == null) return m;
        for (String par : raw.split("&")) {
            if (par.isEmpty()) continue;
            String[] kv = par.split("=", 2);
            m.put(URLDecoder.decode(kv[0], StandardCharsets.UTF_8), kv.length > 1 ? URLDecoder.decode(kv[1], StandardCharsets.UTF_8) : "");
        }
        return m;
    }

    static void enviar(HttpExchange ex, int status, Object corpo) throws IOException {
        byte[] dados = Json.escrever(corpo).getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        ex.sendResponseHeaders(status, dados.length);
        ex.getResponseBody().write(dados);
    }

    // ---------- arquivos da pasta web/ ----------
    static void estatico(HttpExchange ex, String caminho) throws IOException {
        Path base = Path.of("web").toAbsolutePath().normalize();
        Path f = base.resolve(caminho.equals("/") ? "index.html" : caminho.substring(1)).normalize();
        if (!f.startsWith(base) || !Files.isRegularFile(f)) {
            byte[] msg = "Página não encontrada".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(404, msg.length);
            ex.getResponseBody().write(msg);
            return;
        }
        String nome = f.getFileName().toString();
        String tipo = nome.endsWith(".html") ? "text/html; charset=utf-8"
                    : nome.endsWith(".css")  ? "text/css; charset=utf-8"
                    : nome.endsWith(".js")   ? "application/javascript; charset=utf-8"
                    : "application/octet-stream";
        byte[] dados = Files.readAllBytes(f);
        ex.getResponseHeaders().set("Content-Type", tipo);
        ex.sendResponseHeaders(200, dados.length);
        ex.getResponseBody().write(dados);
    }
}
