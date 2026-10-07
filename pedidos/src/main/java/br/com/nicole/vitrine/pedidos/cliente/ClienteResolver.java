package br.com.nicole.vitrine.pedidos.cliente;

import org.springframework.core.MethodParameter;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.stereotype.Component;
import org.springframework.web.bind.support.WebDataBinderFactory;
import org.springframework.web.context.request.NativeWebRequest;
import org.springframework.web.method.support.HandlerMethodArgumentResolver;
import org.springframework.web.method.support.ModelAndViewContainer;
import org.springframework.web.server.ResponseStatusException;

/**
 * Tira o cliente do token JWT ja validado: "sub" e o id que o Keycloak deu ao
 * usuario, "email" vai para o aviso do pedido. Nada vem de cabecalho ou corpo
 * que o proprio cliente possa escrever.
 */
@Component
public class ClienteResolver implements HandlerMethodArgumentResolver {

    @Override
    public boolean supportsParameter(MethodParameter parametro) {
        return parametro.getParameterType().equals(Cliente.class);
    }

    @Override
    public Cliente resolveArgument(
            MethodParameter parametro, ModelAndViewContainer mav, NativeWebRequest requisicao, WebDataBinderFactory f) {
        if (!(SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token)) {
            throw new ResponseStatusException(HttpStatus.UNAUTHORIZED, "Cliente não identificado");
        }
        String email = token.getToken().getClaimAsString("email");
        if (email == null || email.isBlank()) {
            // Sem e-mail no token nao ha como avisar o cliente sobre o pedido.
            throw new ResponseStatusException(HttpStatus.FORBIDDEN, "O token não traz o e-mail do cliente");
        }
        return new Cliente(token.getToken().getSubject(), email);
    }
}
