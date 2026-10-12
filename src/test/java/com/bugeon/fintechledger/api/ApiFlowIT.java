package com.bugeon.fintechledger.api;

import com.bugeon.fintechledger.support.IntegrationTest;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import java.util.Map;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** The whole HTTP stack — JWT filter, validation, controllers, services — against PostgreSQL. */
class ApiFlowIT extends IntegrationTest {

    @Autowired MockMvc      mvc;
    @Autowired ObjectMapper json;

    @Test
    void signupLoginOpenAccountsMoveMoneyAndReadHistory() throws Exception {
        String email = "api-" + UUID.randomUUID() + "@test.io";
        call(post("/api/v1/auth/signup"), null, Map.of("email", email, "password", "s3cure-pass", "fullName", "Api User"))
                .andExpect(status().isCreated());
        JsonNode tokens = body(call(post("/api/v1/auth/login"), null, Map.of("email", email, "password", "s3cure-pass"))
                .andExpect(status().isOk()));
        String bearer = "Bearer " + tokens.at("/data/accessToken").asText();

        String main = body(call(post("/api/v1/accounts"), bearer, Map.of("accountName", "main", "currency", "KRW"))
                .andExpect(status().isCreated())).at("/data/accountId").asText();
        String savings = body(call(post("/api/v1/accounts"), bearer, Map.of("accountName", "savings", "currency", "KRW"))
                .andExpect(status().isCreated())).at("/data/accountId").asText();

        String depositKey = UUID.randomUUID().toString();
        Map<String, Object> deposit = Map.of("accountId", main, "amount", 50000, "currency", "KRW");
        String firstId = body(call(post("/api/v1/transactions/deposit").header("Idempotency-Key", depositKey), bearer, deposit)
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.data.status").value("SUCCESS"))).at("/data/transactionId").asText();
        String replayId = body(call(post("/api/v1/transactions/deposit").header("Idempotency-Key", depositKey), bearer, deposit)
                .andExpect(status().isCreated())).at("/data/transactionId").asText();
        assertThat(replayId).isEqualTo(firstId);

        call(post("/api/v1/transactions/transfer").header("Idempotency-Key", UUID.randomUUID().toString()), bearer,
                Map.of("fromAccountId", main, "toAccountId", savings, "amount", 20000, "currency", "krw"))
                .andExpect(status().isCreated());

        call(get("/api/v1/accounts/" + main + "/balance"), bearer, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.balance").value(30000.00));
        call(get("/api/v1/transactions").param("accountId", main), bearer, null)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.content.length()").value(2))
                .andExpect(jsonPath("$.data.totalElements").value(2));
        call(get("/api/v1/transactions").param("accountId", main).param("sort", "noSuchField"), bearer, null)
                .andExpect(status().isBadRequest());

        call(post("/api/v1/transactions/withdraw").header("Idempotency-Key", UUID.randomUUID().toString()), bearer,
                Map.of("accountId", main, "amount", 10.5, "currency", "KRW"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.error.code").value("TXN_004"));
        call(post("/api/v1/transactions/withdraw").header("Idempotency-Key", UUID.randomUUID().toString()), bearer,
                Map.of("accountId", main, "amount", 999999, "currency", "KRW"))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.error.code").value("TXN_001"));

        call(get("/api/v1/no-such-endpoint"), bearer, null)
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.error.code").value("GEN_404"));
        call(get("/api/v1/transactions/deposit"), bearer, null)
                .andExpect(status().isMethodNotAllowed());

        JsonNode rotated = body(call(post("/api/v1/auth/refresh"), null,
                Map.of("refreshToken", tokens.at("/data/refreshToken").asText())).andExpect(status().isOk()));
        assertThat(rotated.at("/data/refreshToken").asText()).isNotEqualTo(tokens.at("/data/refreshToken").asText());
    }

    @Test
    void protectedEndpointsRequireAToken() throws Exception {
        call(post("/api/v1/transactions/deposit").header("Idempotency-Key", "k"), null,
                Map.of("accountId", UUID.randomUUID().toString(), "amount", 1, "currency", "KRW"))
                .andExpect(status().isUnauthorized());
        call(get("/api/v1/accounts"), "Bearer not-a-jwt", null).andExpect(status().isUnauthorized());
    }

    private ResultActions call(MockHttpServletRequestBuilder request, String bearer, Object body) throws Exception {
        if (bearer != null) {
            request.header("Authorization", bearer);
        }
        if (body != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(json.writeValueAsString(body));
        }
        return mvc.perform(request);
    }

    private JsonNode body(ResultActions result) throws Exception {
        return json.readTree(result.andReturn().getResponse().getContentAsString());
    }
}
