import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;
import java.util.Map;

final class DashboardService {
    private static final ZoneId FUSO = ZoneId.of("America/Sao_Paulo");
    private static final int MAX_MESES = 120;

    // RF13: uma instrução SQL mantém todos os indicadores na mesma visão do banco.
    private static final String RELATORIO = """
        WITH limites AS (
            SELECT CAST(? AS date) AS inicio, CAST(? AS date) AS fim, CAST(? AS text) AS fuso
        ), periodo AS (
            SELECT inicio, fim, fuso,
                   inicio::timestamp AT TIME ZONE fuso AS inicio_oficina,
                   fim::timestamp AT TIME ZONE fuso AS fim_oficina
            FROM limites
        ), vendas_periodo AS (
            SELECT v.data, v.valor, v.veiculo_id
            FROM vendas v CROSS JOIN periodo p
            WHERE v.data >= p.inicio AND v.data < p.fim
        ), vendas_mensais AS (
            SELECT date_trunc('month', data::timestamp)::date AS mes,
                   count(*) AS qtd, sum(valor) AS receita
            FROM vendas_periodo GROUP BY 1
        ), test_drives_mensais AS (
            SELECT date_trunc('month', t.data_hora)::date AS mes,
                   count(*) AS qtd, count(*) FILTER (WHERE t.status = ?) AS concluidos
            FROM test_drives t CROSS JOIN periodo p
            WHERE t.data_hora >= p.inicio AND t.data_hora < p.fim
            GROUP BY 1
        ), agendamentos_mensais AS (
            SELECT date_trunc('month', a.criado_em AT TIME ZONE p.fuso)::date AS mes, count(*) AS qtd
            FROM oficina_agendamentos a CROSS JOIN periodo p
            WHERE a.criado_em >= p.inicio_oficina AND a.criado_em < p.fim_oficina
            GROUP BY 1
        ), aberturas_mensais AS (
            SELECT date_trunc('month', o.aberta_em AT TIME ZONE p.fuso)::date AS mes, count(*) AS qtd
            FROM oficina_os o CROSS JOIN periodo p
            WHERE o.aberta_em >= p.inicio_oficina AND o.aberta_em < p.fim_oficina
            GROUP BY 1
        ), conclusoes_mensais AS (
            SELECT date_trunc('month', o.concluida_em AT TIME ZONE p.fuso)::date AS mes, count(*) AS qtd
            FROM oficina_os o CROSS JOIN periodo p
            WHERE o.concluida_em >= p.inicio_oficina AND o.concluida_em < p.fim_oficina
            GROUP BY 1
        ), faturas_mensais AS (
            SELECT date_trunc('month', f.emitida_em AT TIME ZONE p.fuso)::date AS mes,
                   count(*) AS qtd, sum(f.subtotal) AS subtotal, sum(f.imposto) AS impostos, sum(f.total) AS receita
            FROM oficina_faturas f CROSS JOIN periodo p
            WHERE f.emitida_em >= p.inicio_oficina AND f.emitida_em < p.fim_oficina
            GROUP BY 1
        ), meses AS (
            SELECT serie.mes::date AS mes
            FROM periodo p
            CROSS JOIN LATERAL generate_series(date_trunc('month', p.inicio::timestamp),
                date_trunc('month', (p.fim - 1)::timestamp), INTERVAL '1 month') AS serie(mes)
        ), painel AS (
            SELECT to_char(mes, 'YYYY-MM') AS mes,
                   coalesce(v.qtd, 0) AS vendas, coalesce(v.receita, 0) AS receita,
                   coalesce(t.qtd, 0) AS "testDrives", coalesce(t.concluidos, 0) AS "testDrivesConcluidos",
                   coalesce(a.qtd, 0) AS "agendamentosOficina",
                   coalesce(o.qtd, 0) AS "osAbertas", coalesce(co.qtd, 0) AS "osConcluidas",
                   coalesce(f.qtd, 0) AS faturas, coalesce(f.subtotal, 0) AS "subtotalOficina",
                   coalesce(f.impostos, 0) AS "impostosOficina", coalesce(f.receita, 0) AS "receitaOficina"
            FROM meses
            LEFT JOIN vendas_mensais v USING (mes)
            LEFT JOIN test_drives_mensais t USING (mes)
            LEFT JOIN agendamentos_mensais a USING (mes)
            LEFT JOIN aberturas_mensais o USING (mes)
            LEFT JOIN conclusoes_mensais co USING (mes)
            LEFT JOIN faturas_mensais f USING (mes)
        ), mais_vendidos AS (
            SELECT ve.marca || ' ' || ve.modelo AS modelo, count(*) AS qtd
            FROM vendas_periodo v JOIN veiculos ve ON ve.id = v.veiculo_id
            GROUP BY 1 ORDER BY qtd DESC, modelo LIMIT 10
        )
        SELECT json_build_object(
            'inicio', p.inicio, 'fim', p.fim,
            'meses', (SELECT json_agg(m ORDER BY m.mes) FROM painel m),
            'maisVendidos', (SELECT coalesce(json_agg(v ORDER BY v.qtd DESC, v.modelo), '[]'::json) FROM mais_vendidos v),
            'totais', (SELECT json_build_object(
                'vendas', sum(vendas), 'receitaVendas', sum(receita),
                'testDrives', sum("testDrives"), 'testDrivesConcluidos', sum("testDrivesConcluidos"),
                'agendamentosOficina', sum("agendamentosOficina"),
                'osAbertas', sum("osAbertas"), 'osConcluidas', sum("osConcluidas"),
                'faturas', sum(faturas), 'subtotalOficina', sum("subtotalOficina"),
                'impostosOficina', sum("impostosOficina"), 'receitaOficina', sum("receitaOficina")
            ) FROM painel)
        )::text AS relatorio
        FROM periodo p
        """;

    private record Periodo(LocalDate inicio, LocalDate fim) {}

    static Map<String, Object> relatorio(Connection c, Map<String, String> filtros) throws SQLException {
        Periodo periodo = periodo(filtros);
        Map<String, Object> resultado = Banco.um(c, RELATORIO,
            periodo.inicio(), periodo.fim(), FUSO.getId(), TestDriveService.CONCLUIDO);
        return Json.objeto((String) resultado.get("relatorio"));
    }

    private static Periodo periodo(Map<String, String> filtros) {
        String inicio = filtros.get("inicio"), fim = filtros.get("fim"), meses = filtros.get("meses");
        if (inicio == null && fim == null) {
            int quantidade = 1;
            if (meses != null) {
                try {
                    quantidade = Integer.parseInt(meses);
                } catch (NumberFormatException e) {
                    throw ErroNegocio.invalido("Informe meses como número inteiro de 1 a " + MAX_MESES + ".");
                }
            }
            if (quantidade < 1 || quantidade > MAX_MESES) {
                throw ErroNegocio.invalido("Informe meses como número inteiro de 1 a " + MAX_MESES + ".");
            }
            YearMonth atual = YearMonth.now(FUSO);
            return new Periodo(atual.minusMonths(quantidade - 1).atDay(1), atual.plusMonths(1).atDay(1));
        }
        if (inicio == null || fim == null || meses != null) {
            throw ErroNegocio.invalido("Informe inicio e fim juntos ou use somente o filtro meses.");
        }
        LocalDate dataInicio = data(inicio), dataFim = data(fim);
        if (!dataInicio.isBefore(dataFim)) {
            throw ErroNegocio.invalido("A data final deve ser posterior à inicial.");
        }
        if (ChronoUnit.MONTHS.between(YearMonth.from(dataInicio), YearMonth.from(dataFim.minusDays(1))) >= MAX_MESES) {
            throw ErroNegocio.invalido("O período deve abranger no máximo " + MAX_MESES + " meses de calendário.");
        }
        return new Periodo(dataInicio, dataFim);
    }

    private static LocalDate data(String valor) {
        try {
            LocalDate data = LocalDate.parse(valor);
            if (valor.length() == 10 && data.getYear() >= 1) return data;
        } catch (DateTimeParseException e) {
            // A mesma mensagem cobre datas inválidas e anos fora do formato aceito.
        }
        throw ErroNegocio.invalido("Informe as datas no formato AAAA-MM-DD.");
    }
}
