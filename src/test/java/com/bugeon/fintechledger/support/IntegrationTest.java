package com.bugeon.fintechledger.support;

import com.bugeon.fintechledger.account.dto.CreateAccountRequest;
import com.bugeon.fintechledger.account.repository.AccountRepository;
import com.bugeon.fintechledger.account.service.AccountService;
import com.bugeon.fintechledger.auth.domain.User;
import com.bugeon.fintechledger.auth.repository.UserRepository;
import com.bugeon.fintechledger.auth.security.UserPrincipal;
import com.bugeon.fintechledger.common.exception.BusinessException;
import com.bugeon.fintechledger.common.exception.ErrorCode;
import com.bugeon.fintechledger.transaction.dto.DepositRequest;
import com.bugeon.fintechledger.transaction.service.TransactionService;
import org.assertj.core.api.ThrowableAssert.ThrowingCallable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.containers.PostgreSQLContainer;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Full application context against a real PostgreSQL 16 (Flyway V1..latest, Hibernate
 * schema validation). One container is shared by every integration test class.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class IntegrationTest {

    protected static final PostgreSQLContainer<?> POSTGRES = new PostgreSQLContainer<>("postgres:16-alpine");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected UserRepository     userRepository;
    @Autowired protected AccountService     accountService;
    @Autowired protected AccountRepository  accountRepository;
    @Autowired protected TransactionService transactionService;
    @Autowired protected JdbcTemplate       jdbc;

    protected UserPrincipal newUser() {
        String email = "user-" + UUID.randomUUID() + "@test.io";
        User user = userRepository.save(User.create(email, "{noop}unused", "Test User"));
        return UserPrincipal.of(user.getId(), user.getEmail());
    }

    protected UUID newAccount(UserPrincipal owner, String currency) {
        return accountService.createAccount(new CreateAccountRequest("main", currency), owner).accountId();
    }

    protected UUID fundedAccount(UserPrincipal owner, String amount) {
        UUID accountId = newAccount(owner, "KRW");
        transactionService.deposit(key(), new DepositRequest(accountId, new BigDecimal(amount), "KRW", "seed"), owner);
        return accountId;
    }

    protected BigDecimal balance(UUID accountId) {
        return accountRepository.findById(accountId).orElseThrow().getCachedBalance();
    }

    protected static String key() {
        return UUID.randomUUID().toString();
    }

    protected static void assertRejected(ThrowingCallable call, ErrorCode expected) {
        assertThatThrownBy(call).isInstanceOfSatisfying(BusinessException.class,
                e -> assertThat(e.getErrorCode()).isEqualTo(expected));
    }

    /** Starts all tasks at the same instant and returns their results; any task failure fails the test. */
    protected static <T> List<T> runConcurrently(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> task : tasks) {
                futures.add(pool.submit(() -> {
                    start.await();
                    return task.call();
                }));
            }
            start.countDown();
            List<T> results = new ArrayList<>();
            for (Future<T> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }
}
