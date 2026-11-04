package com.ragpeat.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.mock.web.MockHttpServletRequest;

class AdminControllerTest {

    private static final String KEY = "clave-de-prueba-de-mas-de-veinte-letras";

    private AdminController controller;

    @BeforeEach
    void setUp() {
        ClientInfo clientInfo = mock(ClientInfo.class);
        when(clientInfo.ip(any())).thenReturn("1.1.1.1");
        controller = new AdminController(null, clientInfo, KEY);
    }

    private static MockHttpServletRequest withKey(String key) {
        MockHttpServletRequest request = new MockHttpServletRequest();
        request.addHeader("Authorization", "Bearer " + key);
        return request;
    }

    @Test
    void laClaveBuena_abreSesionEnCookieQueJavaScriptNoPuedeLeer() {
        ResponseEntity<?> response = controller.login(withKey(KEY));

        assertThat(response.getStatusCode().value()).isEqualTo(204);
        String cookie = response.getHeaders().getFirst(HttpHeaders.SET_COOKIE);
        assertThat(cookie).startsWith(AdminController.SESSION_COOKIE + "=")
                .contains("HttpOnly", "Secure", "SameSite=Strict", "Path=/api/admin")
                .doesNotContain(KEY);
    }

    @Test
    void pasadoElLimiteDeIntentos_niSiquieraLaClaveBuenaEntra() {
        for (int i = 0; i < 10; i++) {
            assertThat(controller.login(withKey("mala")).getStatusCode().value()).isEqualTo(401);
        }

        assertThat(controller.login(withKey("mala")).getStatusCode().value()).isEqualTo(429);
        assertThat(controller.login(withKey(KEY)).getStatusCode().value()).isEqualTo(429);
    }

    @Test
    void conClaveCorta_elPanelNoExiste() {
        AdminController off = new AdminController(null, mock(ClientInfo.class), "corta");

        assertThat(off.login(withKey("corta")).getStatusCode().value()).isEqualTo(404);
        assertThat(off.logout(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(404);
    }

    @Test
    void sinSesionNiClave_noHayDatos_yNoCuentaComoIntentoFallido() {
        for (int i = 0; i < 15; i++) {
            assertThat(controller.summary(new MockHttpServletRequest()).getStatusCode().value()).isEqualTo(401);
        }

        assertThat(controller.login(withKey(KEY)).getStatusCode().value()).isEqualTo(204);
    }
}
