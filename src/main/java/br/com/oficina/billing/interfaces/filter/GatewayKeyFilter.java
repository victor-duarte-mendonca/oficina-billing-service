package br.com.oficina.billing.interfaces.filter;

import jakarta.annotation.Priority;
import jakarta.ws.rs.Priorities;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.ext.Provider;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Map;
import java.util.Optional;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Todo tráfego de negócio só pelo API Gateway (mesma regra do OS): exige o {@code X-Gateway-Key} que o gateway
 * injeta em {@code /admin}, {@code /me}, {@code /public} e {@code /webhooks}. Desligado com a chave vazia
 * (dev/test/docker). Health e métricas ficam de fora (kubelet e scrape interno).
 */
@Provider
@Priority(Priorities.AUTHENTICATION - 100)
public class GatewayKeyFilter implements ContainerRequestFilter {

    static final String HEADER = "X-Gateway-Key";

    private final byte[] expectedKey;

    /** {@code @ConfigProperty}: o provider JAX-RS é criado antes do mapeamento {@code BillingConfig} existir. */
    public GatewayKeyFilter(@ConfigProperty(name = "app.gateway.key") Optional<String> gatewayKey) {
        this.expectedKey = gatewayKey.filter(key -> !key.isBlank())
            .map(key -> key.getBytes(StandardCharsets.UTF_8))
            .orElse(null);
    }

    @Override
    public void filter(ContainerRequestContext request) {
        if (expectedKey == null || !isGatewayProtected(request.getUriInfo().getPath())) {
            return;
        }
        String provided = request.getHeaderString(HEADER);
        if (provided == null || !MessageDigest.isEqual(provided.getBytes(StandardCharsets.UTF_8), expectedKey)) {
            request.abortWith(Response.status(Response.Status.FORBIDDEN)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("status", 403, "message", "Acesso negado"))
                .build());
        }
    }

    static boolean isGatewayProtected(String path) {
        String normalized = path.startsWith("/") ? path.substring(1) : path;
        return !(normalized.equals("q") || normalized.startsWith("q/"));
    }
}
