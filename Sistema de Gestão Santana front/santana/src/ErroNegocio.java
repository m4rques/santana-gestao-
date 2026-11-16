// Erro de regra de negócio: carrega o código HTTP e a mensagem mostrada no front.
public class ErroNegocio extends RuntimeException {
    public final int status;

    public ErroNegocio(int status, String mensagem) {
        super(mensagem);
        this.status = status;
    }

    static ErroNegocio invalido(String m)       { return new ErroNegocio(400, m); }
    static ErroNegocio naoAutorizado(String m)  { return new ErroNegocio(401, m); }
    static ErroNegocio proibido(String m)       { return new ErroNegocio(403, m); }
    static ErroNegocio naoEncontrado(String m)  { return new ErroNegocio(404, m); }
    static ErroNegocio conflito(String m)       { return new ErroNegocio(409, m); }
}
