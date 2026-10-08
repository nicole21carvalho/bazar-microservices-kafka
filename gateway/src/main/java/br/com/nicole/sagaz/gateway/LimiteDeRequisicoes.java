package br.com.nicole.sagaz.gateway;

import java.io.IOException;
import java.time.Duration;
import java.util.concurrent.TimeUnit;

import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.filter.OncePerRequestFilter;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import io.github.bucket4j.Bucket;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

/**
 * Protege a loja de abuso antes de a requisicao chegar aos servicos:
 *
 * - limite geral por usuario (ou por IP, para quem nao esta logado);
 * - limite menor para criar pedido, que dispara a saga inteira (estoque,
 *   pagamento, e-mail) e e a operacao mais cara do sistema;
 * - tamanho maximo do corpo, para ninguem mandar megabytes de JSON.
 *
 * Os contadores ficam na memoria de cada replica do gateway: com 3 replicas, o
 * limite efetivo e ate 3 vezes maior. Para um limite exato entre replicas, o
 * Bucket4j guarda os contadores no Redis com a mesma API.
 */
public class LimiteDeRequisicoes extends OncePerRequestFilter {

    private final int geralPorMinuto;
    private final int pedidosPorMinuto;
    private final long corpoMaximo;

    // Um contador por chave, com tamanho maximo e expiracao: um ataque com
    // milhoes de IPs diferentes nao esgota a memoria do gateway.
    private final Cache<String, Bucket> gerais = cache();
    private final Cache<String, Bucket> criacoes = cache();

    public LimiteDeRequisicoes(int geralPorMinuto, int pedidosPorMinuto, long corpoMaximo) {
        this.geralPorMinuto = geralPorMinuto;
        this.pedidosPorMinuto = pedidosPorMinuto;
        this.corpoMaximo = corpoMaximo;
    }

    private static Cache<String, Bucket> cache() {
        return Caffeine.newBuilder().maximumSize(100_000).expireAfterAccess(Duration.ofMinutes(10)).build();
    }

    private static Bucket balde(int porMinuto) {
        return Bucket.builder()
                .addLimit(limite -> limite.capacity(porMinuto).refillGreedy(porMinuto, Duration.ofMinutes(1)))
                .build();
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest requisicao) {
        return !requisicao.getRequestURI().startsWith("/api/");
    }

    @Override
    protected void doFilterInternal(HttpServletRequest req, HttpServletResponse resp, FilterChain cadeia)
            throws ServletException, IOException {
        if (temCorpo(req)) {
            long tamanho = req.getContentLengthLong();
            if (tamanho < 0) {
                // Corpo sem tamanho declarado (chunked) nao da para limitar antes
                // de ler. Navegador sempre declara o tamanho num POST com JSON.
                recusar(resp, HttpStatus.LENGTH_REQUIRED, "Informe o tamanho do corpo (Content-Length).", 0);
                return;
            }
            if (tamanho > corpoMaximo) {
                recusar(resp, HttpStatus.CONTENT_TOO_LARGE, "Corpo da requisição grande demais.", 0);
                return;
            }
        }

        String chave = chave(req);
        if (!consumir(gerais.get(chave, k -> balde(geralPorMinuto)), resp)) {
            return;
        }
        boolean criandoPedido = "POST".equals(req.getMethod()) && req.getRequestURI().equals("/api/pedidos");
        if (criandoPedido && !consumir(criacoes.get(chave, k -> balde(pedidosPorMinuto)), resp)) {
            return;
        }
        cadeia.doFilter(req, resp);
    }

    private static boolean temCorpo(HttpServletRequest req) {
        return switch (req.getMethod()) {
            case "POST", "PUT", "PATCH" -> true;
            default -> false;
        };
    }

    /**
     * Usuario logado: o "sub" do token, entao trocar de IP nao zera o limite.
     * Anonimo: o IP. Atras de um proxy (Ingress), o IP visto e o do proxy:
     * configure server.forward-headers-strategy para usar o X-Forwarded-For dele.
     */
    private static String chave(HttpServletRequest req) {
        if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
            return "usuario:" + token.getToken().getSubject();
        }
        return "ip:" + req.getRemoteAddr();
    }

    private boolean consumir(Bucket balde, HttpServletResponse resp) throws IOException {
        var resultado = balde.tryConsumeAndReturnRemaining(1);
        if (resultado.isConsumed()) {
            return true;
        }
        long segundos = Math.max(1, TimeUnit.NANOSECONDS.toSeconds(resultado.getNanosToWaitForRefill()) + 1);
        recusar(resp, HttpStatus.TOO_MANY_REQUESTS, "Muitas requisições. Tente de novo em instantes.", segundos);
        return false;
    }

    private static void recusar(HttpServletResponse resp, HttpStatus status, String detalhe, long segundos)
            throws IOException {
        resp.setStatus(status.value());
        if (segundos > 0) {
            resp.setHeader("Retry-After", Long.toString(segundos));
        }
        resp.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        resp.setCharacterEncoding("UTF-8");
        resp.getWriter().write("{\"status\":" + status.value() + ",\"title\":\"" + status.getReasonPhrase()
                + "\",\"detail\":\"" + detalhe + "\"}");
    }
}
