package com.tazzzo.catalog;

import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * CVE-2026-24880 (Tomcat before 10.1.52): request smuggling through invalid chunk extensions. A connector that accepts a
 * chunk-size line a front-end proxy reads differently lets the two disagree about where one request ends. Before the
 * upgrade (10.1.31) every malformed extension below was accepted and the body handed to the application; the patched
 * connector refuses the request with 400 while a well-formed extension still reaches the application.
 *
 * <p>Raw sockets, because an HTTP client normalises exactly the bytes under test. The well-formed request is a valid
 * OTP request; with no OTP provider in this suite the application answers it with its fail-closed 503, so a 400 can
 * only come from the connector refusing the framing.
 */
class HttpRequestSmugglingIT extends AbstractApiIT {

    static final String JSON = "{\"phone\":\"+919876543210\"}";

    private String send(String chunkSizeLine) throws IOException {
        String hex = Integer.toHexString(JSON.length());
        String request = "POST /v1/auth/otp/request HTTP/1.1\r\nHost: localhost\r\nContent-Type: application/json\r\n"
                + "Transfer-Encoding: chunked\r\nConnection: close\r\n\r\n"
                + chunkSizeLine.replace("SIZE", hex) + "\r\n" + JSON + "\r\n0\r\n\r\n";
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setSoTimeout(10_000);
            socket.getOutputStream().write(request.getBytes(StandardCharsets.ISO_8859_1));
            socket.getOutputStream().flush();
            return new String(socket.getInputStream().readAllBytes(), StandardCharsets.ISO_8859_1);
        }
    }

    private static int status(String response) {
        assertThat(response).startsWith("HTTP/1.1 ");
        return Integer.parseInt(response.substring(9, 12));
    }

    @Test
    void a_well_formed_chunk_extension_reaches_the_application() throws IOException {
        for (String line : new String[]{"SIZE", "SIZE;name=value", "SIZE;name=\"quoted value\"", "SIZE;a=1;b"}) {
            String response = send(line);
            assertThat(status(response)).as(line).isNotEqualTo(400);
            assertThat(response.toLowerCase()).as(line + ": answered by the application").contains("x-request-id: req_");
        }
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "SIZE;name=\"unterminated",        // unterminated quoted-string
            "SIZE;na\u0001me=value",            // control character
            "SIZE;=value",                      // extension without a name
            "SIZE;n@me=value",                  // separator inside the name token
            "SIZE;name=val\"ue"                 // quote inside a token value
    })
    void a_malformed_chunk_extension_is_refused_instead_of_parsed(String line) throws IOException {
        String response = send(line);
        assertThat(status(response)).as("the connector must refuse, not hand the body to the application").isEqualTo(400);
    }
}
