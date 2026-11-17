package br.com.oficina;

import java.io.IOException;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class ContratoPdf {
    private static final Pattern CAMPO = Pattern.compile("\\{\\{[ \\t]*([a-zA-Z][a-zA-Z0-9_.-]*)[ \\t]*}}");
    private static final Charset WIN_ANSI = Charset.forName("windows-1252");
    private static final int COLUNAS = 75;
    private static final int LINHAS_POR_PAGINA = 53;

    private ContratoPdf() {
    }

    public static Path gerar(Path template, Path destino, Map<String, String> dados) throws IOException {
        Path arquivo = destino.toAbsolutePath().normalize();
        if (arquivo.getParent() == null || Files.isDirectory(arquivo)) {
            throw new IllegalArgumentException("O destino do PDF precisa ser um arquivo.");
        }
        if (Files.exists(arquivo) && Files.isSameFile(template, arquivo)) {
            throw new IllegalArgumentException("O template e o destino precisam ser arquivos distintos.");
        }
        byte[] pdf = gerar(template, dados);

        Files.createDirectories(arquivo.getParent());
        Path temporario = Files.createTempFile(arquivo.getParent(), "contrato-", ".tmp");
        try {
            Files.write(temporario, pdf);
            try {
                Files.move(temporario, arquivo, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException e) {
                Files.move(temporario, arquivo, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporario);
        }
        return arquivo;
    }

    public static byte[] gerar(Path template, Map<String, String> dados) throws IOException {
        String modelo = Files.readString(template, StandardCharsets.UTF_8);
        String conteudo = mesclar(modelo, dados);
        return montarPdf(quebrarLinhas(conteudo));
    }

    private static String mesclar(String modelo, Map<String, String> dados) {
        if (modelo.startsWith("\uFEFF")) {
            modelo = modelo.substring(1);
        }
        Matcher campo = CAMPO.matcher(modelo);
        StringBuilder texto = new StringBuilder();
        int posicao = 0;
        while (campo.find()) {
            texto.append(trechoLiteral(modelo, posicao, campo.start()));
            String nome = campo.group(1);
            String valor = dados.get(nome);
            if (valor == null || valor.isBlank()) {
                throw new IllegalArgumentException("Campo do template sem valor: " + nome);
            }
            texto.append(valor);
            posicao = campo.end();
        }
        texto.append(trechoLiteral(modelo, posicao, modelo.length()));
        String conteudo = Normalizer.normalize(texto, Normalizer.Form.NFC).replace("\t", "    ");
        if (conteudo.isBlank()) {
            throw new IllegalArgumentException("O contrato está vazio.");
        }
        boolean controleInvalido = conteudo.chars().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r');
        if (controleInvalido || !WIN_ANSI.newEncoder().canEncode(conteudo)) {
            throw new IllegalArgumentException("O texto contém caracteres incompatíveis com a fonte do PDF (Windows-1252).");
        }
        return conteudo;
    }

    private static String trechoLiteral(String modelo, int inicio, int fim) {
        String trecho = modelo.substring(inicio, fim);
        if (trecho.contains("{{") || trecho.contains("}}")) {
            throw new IllegalArgumentException("Campo inválido no template. Use {{nome}}.");
        }
        return trecho;
    }

    private static List<String> quebrarLinhas(String conteudo) {
        List<String> linhas = new ArrayList<>();
        conteudo.lines().forEach(linha -> {
            String restante = linha.stripTrailing();
            while (restante.length() > COLUNAS) {
                int separador = restante.lastIndexOf(' ', COLUNAS);
                int corte = separador > 0 && !restante.substring(0, separador).isBlank() ? separador : COLUNAS;
                linhas.add(restante.substring(0, corte).stripTrailing());
                restante = restante.substring(corte).stripLeading();
            }
            linhas.add(restante);
        });
        return linhas;
    }

    private static byte[] montarPdf(List<String> linhas) {
        int paginas = (linhas.size() - 1) / LINHAS_POR_PAGINA + 1;
        StringBuilder referencias = new StringBuilder();
        for (int pagina = 0; pagina < paginas; pagina++) {
            referencias.append(4 + 2 * pagina).append(" 0 R ");
        }

        List<String> objetos = new ArrayList<>();
        objetos.add("<< /Type /Catalog /Pages 2 0 R >>");
        objetos.add("<< /Type /Pages /Count " + paginas + " /Kids [" + referencias + "] >>");
        objetos.add("<< /Type /Font /Subtype /Type1 /BaseFont /Courier /Encoding /WinAnsiEncoding >>");
        for (int pagina = 0; pagina < paginas; pagina++) {
            int stream = 5 + 2 * pagina;
            objetos.add("<< /Type /Page /Parent 2 0 R /MediaBox [0 0 595 842] "
                    + "/Resources << /Font << /F1 3 0 R >> >> /Contents " + stream + " 0 R >>");
            int primeiraLinha = pagina * LINHAS_POR_PAGINA;
            int fim = Math.min(primeiraLinha + LINHAS_POR_PAGINA, linhas.size());
            String conteudo = comandosPagina(linhas.subList(primeiraLinha, fim), pagina + 1, paginas);
            objetos.add("<< /Length " + conteudo.length() + " >>\nstream\n" + conteudo + "endstream");
        }

        StringBuilder pdf = new StringBuilder("%PDF-1.4\n");
        StringBuilder referenciasCruzadas = new StringBuilder("0000000000 65535 f \n");
        for (int indice = 0; indice < objetos.size(); indice++) {
            referenciasCruzadas.append(String.format(Locale.ROOT, "%010d 00000 n \n", pdf.length()));
            pdf.append(indice + 1).append(" 0 obj\n").append(objetos.get(indice)).append("\nendobj\n");
        }
        int inicioXref = pdf.length();
        int totalObjetos = objetos.size() + 1;
        pdf.append("xref\n0 ").append(totalObjetos).append('\n').append(referenciasCruzadas);
        pdf.append("trailer\n<< /Size ").append(totalObjetos).append(" /Root 1 0 R >>\n");
        pdf.append("startxref\n").append(inicioXref).append("\n%%EOF\n");
        return pdf.toString().getBytes(StandardCharsets.US_ASCII);
    }

    private static String comandosPagina(List<String> linhas, int pagina, int paginas) {
        StringBuilder comandos = new StringBuilder("BT /F1 11 Tf 14 TL 50 792 Td\n");
        for (String linha : linhas) {
            comandos.append(textoHexadecimal(linha)).append(" Tj T*\n");
        }
        String rodape = textoHexadecimal("Página " + pagina + " de " + paginas);
        return comandos.append("ET\nBT /F1 9 Tf 50 34 Td ").append(rodape).append(" Tj ET\n").toString();
    }

    private static String textoHexadecimal(String texto) {
        return "<" + HexFormat.of().formatHex(texto.getBytes(WIN_ANSI)) + ">";
    }
}
