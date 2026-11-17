import com.sun.net.httpserver.*;
import java.io.IOException;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import br.com.oficina.Notificacoes;

// Servidor: entrega a pasta web/ e a API em /api. Rode esta classe pelo botão Run do VS Code.
public class Main {
    record Resp(int status, Object corpo) {}

    static final Map<String, Sessao> sessoes = new ConcurrentHashMap<>();
    static final long INATIVIDADE_MS = 30 * 60 * 1000; // sessão cai após 30 min sem uso
    private static final Path TEMPLATE = Path.of(System.getenv().getOrDefault("OFICINA_TEMPLATE",
        "src/main/resources/contrato-venda.txt"));
    private static final BigDecimal ALIQUOTA = OficinaService.aliquota(System.getenv().getOrDefault("OFICINA_IMPOSTO", "0.05"));

    public static void main(String[] args) throws Exception {
        Map<String, Notificacoes.Canal> canais = canais();
        Duration intervalo = intervalo();
        try (Connection c = Banco.abrir()) {
            System.out.println("Banco de dados conectado.");
        } catch (SQLException e) {
            System.out.println("ATENÇÃO: não foi possível conectar ao banco: " + e.getMessage());
        }
        HttpServer srv = HttpServer.create(new InetSocketAddress(8080), 0);
        srv.createContext("/", Main::tratar);
        ExecutorService requisicoes = Executors.newFixedThreadPool(8);
        srv.setExecutor(requisicoes);
        ScheduledExecutorService notificacoes = canais.isEmpty() ? null : NotificacaoService.iniciar(canais, intervalo);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            if (notificacoes != null) notificacoes.shutdownNow();
            srv.stop(1);
            requisicoes.shutdownNow();
        }));
        srv.start();
        System.out.println("Servidor em http://localhost:8080");
        System.out.println(canais.isEmpty() ? "Notificações serão enfileiradas até que um canal seja configurado."
            : "Canais de notificação ativos: " + canais.keySet());
    }

    private static Map<String, Notificacoes.Canal> canais() {
        Map<String, Notificacoes.Canal> canais = new LinkedHashMap<>();
        boolean simular = Boolean.parseBoolean(System.getenv("OFICINA_NOTIFICACOES_CONSOLE"));
        for (String canal : List.of("EMAIL", "SMS")) {
            String prefixo = "OFICINA_" + canal;
            String url = System.getenv(prefixo + "_URL");
            if (url != null && !url.isBlank()) {
                canais.put(canal, Notificacoes.http(URI.create(url), System.getenv(prefixo + "_TOKEN")));
            } else if (simular) {
                canais.put(canal, Notificacoes.console(canal));
            }
        }
        return canais;
    }

    private static Duration intervalo() {
        try {
            long segundos = Long.parseLong(System.getenv().getOrDefault("OFICINA_INTERVALO_SEGUNDOS", "30"));
            if (segundos > 0 && segundos <= 86400) return Duration.ofSeconds(segundos);
        } catch (NumberFormatException e) {
        }
        throw new IllegalArgumentException("OFICINA_INTERVALO_SEGUNDOS deve ser um inteiro entre 1 e 86400.");
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
            c.setTransactionIsolation(Connection.TRANSACTION_READ_COMMITTED);
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

    static Resp despachar(Connection c, String m, String[] seg, Map<String, Object> b, Map<String, String> q, Sessao s) throws SQLException, IOException {
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
                if (n == 3 && get && seg[2].equals("contrato.pdf"))
                    return new Resp(200, ContratoService.gerar(c, id(seg[1]), TEMPLATE));
            }
            case "oficina" -> {
                if (n >= 2) return oficina(c, m, seg, b, s);
            }
            case "dashboard" -> {
                if (n == 1 && get) return new Resp(200, DashboardService.relatorio(c, q));
            }
            default -> { }
        }
        throw ErroNegocio.naoEncontrado("Rota não encontrada.");
    }

    private static Resp oficina(Connection c, String m, String[] seg, Map<String, Object> b, Sessao s) throws SQLException {
        boolean get = m.equals("GET"), post = m.equals("POST"), put = m.equals("PUT");
        int n = seg.length;
        switch (seg[1]) {
            case "tecnicos" -> {
                if (n == 2 && get) return new Resp(200, OficinaService.tecnicos(c));
                if (n == 2 && post) {
                    exigir(s, Sessao.ADMIN, Sessao.GERENTE);
                    return new Resp(201, OficinaService.cadastrarTecnico(c, b));
                }
            }
            case "pecas" -> {
                if (n == 2 && get) return new Resp(200, OficinaService.pecas(c));
                if (n == 2 && post) {
                    exigir(s, Sessao.ADMIN, Sessao.GERENTE);
                    return new Resp(201, OficinaService.cadastrarPeca(c, b));
                }
                if (n == 4 && post && seg[3].equals("reposicao")) {
                    exigir(s, Sessao.ADMIN, Sessao.GERENTE);
                    return new Resp(200, OficinaService.reporPeca(c, id(seg[2]), b));
                }
            }
            case "agendamentos" -> {
                if (n == 2 && get) return new Resp(200, OficinaService.agenda(c));
                if (n == 2 && post) return new Resp(201, OficinaService.agendar(c, b));
                if (n == 4 && put && seg[3].equals("cancelamento"))
                    return new Resp(200, OficinaService.cancelar(c, id(seg[2])));
                if (n == 4 && post && seg[3].equals("os"))
                    return new Resp(201, OficinaService.abrirOs(c, id(seg[2])));
            }
            case "os" -> {
                if (n == 2 && get) return new Resp(200, OficinaService.ordens(c));
                if (n == 3 && get) return new Resp(200, OficinaService.consultarOs(c, id(seg[2])));
                if (n == 4 && put && seg[3].equals("inicio"))
                    return new Resp(200, OficinaService.iniciarOs(c, id(seg[2])));
                if (n == 4 && post && seg[3].equals("itens"))
                    return new Resp(201, OficinaService.apontar(c, id(seg[2]), b));
                if (n == 4 && put && seg[3].equals("conclusao"))
                    return new Resp(200, OficinaService.concluirOs(c, id(seg[2])));
                if (n == 4 && post && seg[3].equals("fatura")) {
                    exigir(s, Sessao.ADMIN, Sessao.GERENTE);
                    return new Resp(200, OficinaService.faturar(c, id(seg[2]), ALIQUOTA));
                }
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
        byte[] dados;
        if (corpo instanceof byte[] pdf) {
            dados = pdf;
            ex.getResponseHeaders().set("Content-Type", "application/pdf");
            ex.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"contrato.pdf\"");
            ex.getResponseHeaders().set("Cache-Control", "no-store");
        } else {
            dados = Json.escrever(corpo).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        }
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
