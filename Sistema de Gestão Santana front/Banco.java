import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.sql.*;
import java.util.*;

// Conexão com o PostgreSQL e funções de consulta (JDBC puro).
public class Banco {
    private static final Properties cfg = new Properties();

    static {
        try (var in = new InputStreamReader(new FileInputStream("config.properties"), StandardCharsets.UTF_8)) {
            cfg.load(in);
        } catch (IOException e) {
            System.out.println("Aviso: config.properties não encontrado, usando valores padrão.");
        }
    }

    static Connection abrir() throws SQLException {
        return DriverManager.getConnection(
            cfg.getProperty("db.url", "jdbc:postgresql://localhost:5432/santana"),
            cfg.getProperty("db.user", "postgres"),
            cfg.getProperty("db.password", "postgres"));
    }

    static long longo(Object o) { return ((Number) o).longValue(); }

    static List<Map<String, Object>> lista(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ligar(ps, p);
            try (ResultSet rs = ps.executeQuery()) {
                List<Map<String, Object>> out = new ArrayList<>();
                ResultSetMetaData md = rs.getMetaData();
                while (rs.next()) {
                    Map<String, Object> linha = new LinkedHashMap<>();
                    for (int i = 1; i <= md.getColumnCount(); i++) {
                        Object v = rs.getObject(i);
                        if (v instanceof Timestamp t) v = t.toLocalDateTime().toString();
                        else if (v instanceof java.sql.Date d) v = d.toLocalDate().toString();
                        linha.put(md.getColumnLabel(i), v);
                    }
                    out.add(linha);
                }
                return out;
            }
        }
    }

    // Primeira linha do resultado ou null. Também serve para INSERT ... RETURNING.
    static Map<String, Object> um(Connection c, String sql, Object... p) throws SQLException {
        List<Map<String, Object>> l = lista(c, sql, p);
        return l.isEmpty() ? null : l.get(0);
    }

    static void executar(Connection c, String sql, Object... p) throws SQLException {
        try (PreparedStatement ps = c.prepareStatement(sql)) {
            ligar(ps, p);
            ps.executeUpdate();
        }
    }

    private static void ligar(PreparedStatement ps, Object[] p) throws SQLException {
        for (int i = 0; i < p.length; i++) {
            if (p[i] == null) ps.setNull(i + 1, Types.NULL);
            else ps.setObject(i + 1, p[i]);
        }
    }
}
