package com.chubb.claims.user;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.chubb.claims.common.security.AuthenticatedUser;
import com.chubb.claims.common.security.Role;
import com.fasterxml.jackson.databind.JsonNode;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;

/** Registration and login: the two routes that need no token. */
class AuthTest extends IntegrationTestBase {

    // ---- register ----

    @Test
    void registrationCreatesAClaimantAndNeverReturnsThePassword() throws Exception {
        JsonNode body = read(register("mei.tan", PASSWORD).andExpect(status().isCreated()));
        assertThat(body.get("username").asText()).isEqualTo("mei.tan");
        assertThat(body.get("role").asText()).isEqualTo("CLAIMANT");
        assertThat(body.get("createdAt").asText()).isNotBlank();
        assertThat(body.get("active").asBoolean()).isTrue();
        assertThat(body.fieldNames()).toIterable().containsExactlyInAnyOrder("username", "role", "active", "createdAt");
        assertThat(body.toString()).doesNotContain(PASSWORD).doesNotContain("$2");
    }

    @Test
    void thePasswordIsStoredOnlyAsABcryptHash() throws Exception {
        register("mei.tan", PASSWORD).andExpect(status().isCreated());
        String stored = jdbc.queryForObject("select password_hash from users where username = 'mei.tan'", String.class);
        assertThat(stored).startsWith("$2").hasSize(60).doesNotContain(PASSWORD);
    }

    @Test
    void twoUsersWithTheSamePasswordGetDifferentHashes() throws Exception {
        register("first.user", PASSWORD).andExpect(status().isCreated());
        register("second.user", PASSWORD).andExpect(status().isCreated());
        assertThat(jdbc.queryForList("select distinct password_hash from users", String.class)).hasSize(2);
    }

    @Test
    void registrationCannotChooseARole() throws Exception {
        // an extra "role" field is ignored: self-registration is always a claimant
        mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, ANONYMOUS)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"sneaky\",\"password\":\"" + PASSWORD + "\",\"role\":\"ADMIN\"}"))
                .andExpect(status().isCreated()).andExpect(jsonPath("$.role").value("CLAIMANT"));
        assertThat(jdbc.queryForObject("select role from users where username = 'sneaky'", String.class)).isEqualTo("CLAIMANT");
    }

    @Test
    void aTakenUsernameIs409AndTheFirstAccountIsUntouched() throws Exception {
        register("mei.tan", PASSWORD).andExpect(status().isCreated());
        register("mei.tan", "another-password-1").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USERNAME_TAKEN"));
        login("mei.tan", PASSWORD).andExpect(status().isOk());
        login("mei.tan", "another-password-1").andExpect(status().isUnauthorized());
    }

    @Test
    void invalidRegistrationsAre400() throws Exception {
        String[][] bad = {
                {"", PASSWORD},                 // blank username
                {"ab", PASSWORD},               // too short
                {"Mei.Tan", PASSWORD},          // upper case
                {"mei tan", PASSWORD},          // space
                {"-mei", PASSWORD},             // must start with a letter or digit
                {"a".repeat(51), PASSWORD},     // too long
                {"mei.tan", "short"},           // password under 8
                {"mei.tan", "p".repeat(73)},    // password over 72
                {"mei.tan", ""},
        };
        for (String[] pair : bad) {
            register(pair[0], pair[1]).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isZero();
    }

    @Test
    void aPasswordOf72CharactersButMoreBytesIsRefusedRatherThanSilentlyCut() throws Exception {
        // 40 two-byte characters = 80 bytes, within the 72-character limit but past BCrypt's 72-byte limit
        register("mei.tan", "é".repeat(40)).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PASSWORD_TOO_LONG"));
    }

    @Test
    void missingFieldsAndMalformedJsonAre400() throws Exception {
        mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, ANONYMOUS).contentType(MediaType.APPLICATION_JSON)
                .content("{}")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, ANONYMOUS).contentType(MediaType.APPLICATION_JSON)
                .content("{not json")).andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
        mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, ANONYMOUS).contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("MALFORMED_REQUEST"));
    }

    @Test
    void wrongContentTypeIs415AndWrongMethodIs405() throws Exception {
        mvc.perform(post("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, ANONYMOUS).contentType(MediaType.TEXT_PLAIN)
                .content("x")).andExpect(status().isUnsupportedMediaType()).andExpect(jsonPath("$.code").value("UNSUPPORTED_MEDIA_TYPE"));
        // as an administrator, so the 405 comes from routing and not from the security chain
        mvc.perform(get("/api/v1/auth/register").header(HttpHeaders.AUTHORIZATION, as(Role.ADMIN)))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"));
        mvc.perform(get("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, as(Role.ADMIN)))
                .andExpect(status().isMethodNotAllowed());
    }

    // ---- login ----

    @Test
    void loginReturnsABearerTokenThatCarriesTheUsernameAndRole() throws Exception {
        createUser("olu.officer", Role.CLAIMS_OFFICER);
        JsonNode body = read(login("olu.officer", PASSWORD).andExpect(status().isOk()));
        assertThat(body.get("tokenType").asText()).isEqualTo("Bearer");
        assertThat(body.get("expiresIn").asLong()).isEqualTo(3600);
        assertThat(body.get("username").asText()).isEqualTo("olu.officer");
        assertThat(body.get("role").asText()).isEqualTo("CLAIMS_OFFICER");
        assertThat(jwt.parse(body.get("accessToken").asText())).contains(new AuthenticatedUser("olu.officer", Role.CLAIMS_OFFICER));
        assertThat(body.fieldNames()).toIterable()
                .containsExactlyInAnyOrder("accessToken", "tokenType", "expiresIn", "username", "role");
    }

    @Test
    void theTokenFromLoginIsAcceptedByTheService() throws Exception {
        register("mei.tan", PASSWORD);
        getAs(tokenOf("mei.tan"), "/api/v1/users/me").andExpect(status().isOk()).andExpect(jsonPath("$.username").value("mei.tan"))
                .andExpect(jsonPath("$.role").value("CLAIMANT"));
    }

    @Test
    void anUnknownUserAndAWrongPasswordGiveTheSameAnswer() throws Exception {
        register("mei.tan", PASSWORD);
        String wrongPassword = login("mei.tan", "not-the-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().getResponse().getContentAsString();
        String unknownUser = login("nobody.here", "not-the-password").andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS")).andReturn().getResponse().getContentAsString();
        assertThat(json.readTree(wrongPassword).get("message")).isEqualTo(json.readTree(unknownUser).get("message"));
        assertThat(wrongPassword + unknownUser).doesNotContain("mei.tan").doesNotContain("nobody.here");
    }

    @Test
    void loginIsCaseInsensitiveOnTheUsernameButNotThePassword() throws Exception {
        register("mei.tan", PASSWORD);
        login("MEI.TAN", PASSWORD).andExpect(status().isOk()).andExpect(jsonPath("$.username").value("mei.tan"));
        login("mei.tan", PASSWORD.toUpperCase()).andExpect(status().isUnauthorized());
    }

    @Test
    void loginWithMissingFieldsIs400AndNeverATokenForAnEmptyPassword() throws Exception {
        register("mei.tan", PASSWORD);
        mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, ANONYMOUS).contentType(MediaType.APPLICATION_JSON)
                .content("{\"username\":\"mei.tan\"}")).andExpect(status().isBadRequest());
        login("mei.tan", "").andExpect(status().isBadRequest());
        login("", PASSWORD).andExpect(status().isBadRequest());
    }

    @Test
    void loginIgnoresAnInvalidAuthorizationHeader() throws Exception {
        // an expired or garbage token left in a client must not stop that client from logging in again
        register("mei.tan", PASSWORD);
        mvc.perform(post("/api/v1/auth/login").header(HttpHeaders.AUTHORIZATION, "Bearer garbage.token.value")
                        .contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(Map.of("username", "mei.tan", "password", PASSWORD))))
                .andExpect(status().isOk());
    }

    @Test
    void aSqlInjectionAttemptIsJustAWrongUsername() throws Exception {
        register("mei.tan", PASSWORD);
        login("mei.tan' or '1'='1", "x' or '1'='1").andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from users", Integer.class)).isEqualTo(1);
    }
}
