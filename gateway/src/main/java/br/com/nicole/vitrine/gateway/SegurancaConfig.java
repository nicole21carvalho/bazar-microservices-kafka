package br.com.nicole.vitrine.gateway;

import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.web.cors.CorsConfiguration;
import org.springframework.web.cors.CorsConfigurationSource;
import org.springframework.web.cors.UrlBasedCorsConfigurationSource;

@Configuration
public class SegurancaConfig {

    @Bean
    SecurityFilterChain seguranca(HttpSecurity http) throws Exception {
        return http
                .authorizeHttpRequests(regras -> regras
                        // O catalogo e publico: qualquer um ve os produtos.
                        .requestMatchers(HttpMethod.GET, "/api/produtos", "/api/produtos/**").permitAll()
                        .requestMatchers("/api/pedidos", "/api/pedidos/**").authenticated()
                        .requestMatchers("/actuator/health/**", "/actuator/prometheus").permitAll()
                        // Qualquer outra rota: negada. Rota nova so funciona se
                        // alguem decidir aqui quem pode usa-la.
                        .anyRequest().denyAll())
                .oauth2ResourceServer(oauth -> oauth.jwt(Customizer.withDefaults()))
                // Sem sessao no servidor: cada requisicao traz o proprio token.
                .sessionManagement(s -> s.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
                // CSRF protege sessao por cookie; com token no cabecalho
                // Authorization, outro site nao consegue enviar o token sozinho.
                .csrf(csrf -> csrf.disable())
                .cors(Customizer.withDefaults())
                .build();
    }

    @Bean
    CorsConfigurationSource cors(@Value("${vitrine.cors.origens}") List<String> origens) {
        var config = new CorsConfiguration();
        // Origens explicitas, nunca "*".
        config.setAllowedOrigins(origens);
        config.setAllowedMethods(List.of("GET", "POST"));
        config.setAllowedHeaders(List.of("Authorization", "Content-Type"));
        config.setExposedHeaders(List.of("Location"));
        var fonte = new UrlBasedCorsConfigurationSource();
        fonte.registerCorsConfiguration("/api/**", config);
        return fonte;
    }
}
