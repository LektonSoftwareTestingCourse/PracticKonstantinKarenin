package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthServiceAuthDeclinedCardStatusEvent;
import com.processing.authorization.events.AuthServiceAuthorizeEvent;
import com.processing.authorization.events.AuthServiceDuplicateKeyEvent;
import com.processing.authorization.events.AuthServiceRollbackEvent;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.exceptions.CardNotFoundException;
import com.processing.authorization.exceptions.GetCardException;
import com.processing.authorization.exceptions.InsufficientFundsException;
import com.processing.authorization.exceptions.InternalCardManagerException;
import com.processing.authorization.exceptions.InvalidGetCardRequestException;
import com.processing.authorization.exceptions.InvalidReserveRequestException;
import com.processing.authorization.exceptions.InvalidRollbackRequestException;
import com.processing.authorization.exceptions.PaymentRequiredException;
import com.processing.authorization.exceptions.ReserveException;
import com.processing.authorization.exceptions.RollbackConflictException;
import com.processing.authorization.exceptions.RollbackFailureException;
import com.processing.authorization.exceptions.ServiceUnavailableException;
import com.processing.authorization.repositories.LimitUsageRepository;
import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackRequest;
import com.processing.common.dto.cardmanagement.CardModel;
import com.processing.common.dto.cardmanagement.CardModelStatus;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.web.client.ResourceAccessException;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isA;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AuthServiceImplTest {
    private static final String PAN = "4000001234567899";
    private static final BigDecimal AMOUNT = new BigDecimal("3000.00");
    private static final BigDecimal DAILY = new BigDecimal("15000.00");
    private static final BigDecimal MONTHLY = new BigDecimal("60000.00");
    private static final LocalDate DAY = LocalDate.of(2026, 11, 30);
    private static final AuthorizationRequest REQUEST = AuthorizationRequest.builder()
            .mti("0100").stan("004201").pan(PAN).processingCode("000000").amount(AMOUNT).currencyCode("643")
            .transmissionDateTime(Instant.parse("2026-11-30T23:59:59Z")).terminalId("TERM0042")
            .terminalType("POS").merchantId("MERCHANT0000042").mcc("5411").acquirerId("ACQ042")
            .issuerId("ISSUER_A").build();
    @Mock private LimitUsageRepository limits;
    @Mock private AuthorizationEventNotifier events;
    @Mock private BinLookupClient binLookup;
    @Mock private CardManagementClient cards;
    private AuthServiceImpl service;

    @BeforeEach
    void setUp() {
        service = new AuthServiceImpl(limits, events, binLookup, cards);
    }

    @Test
    void approvesExactBalanceOnLastSecondOfExpiryMonthWithMatchingReservation() {
        givenCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 11), AMOUNT);
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY)).thenReturn(1);
        when(limits.fetchRrnBlock()).thenReturn(42L);

        var response = service.authorize(REQUEST, Instant.now());

        assertThat(response.status()).isEqualTo("APPROVED");
        assertThat(response.responseCode()).isEqualTo("00");
        assertThat(response.stan()).isEqualTo(REQUEST.stan());
        assertThat(response.declineReason()).isNull();
        assertThat(response.rrn()).matches("\\d{12}");
        assertThat(response.authCode()).matches("[0-9A-Z]{6}");
        var rrn = ArgumentCaptor.forClass(String.class);
        var order = inOrder(cards, limits, events);
        order.verify(cards).getCard(PAN);
        order.verify(limits).upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY);
        order.verify(limits).fetchRrnBlock();
        order.verify(cards).reserve(eq(AMOUNT), rrn.capture(), eq(PAN));
        order.verify(events).notify(new AuthServiceAuthorizeEvent(PAN));
        assertThat(rrn.getValue()).isEqualTo(response.rrn());
    }

    @ParameterizedTest
    @CsvSource({"INACTIVE,05,CARD_INACTIVE", "BLOCKED,05,CARD_BLOCKED", "EXPIRED,54,CARD_EXPIRED"})
    void declinesNonActiveStatusesBeforeTouchingLimits(CardModelStatus status, String code, String reason) {
        givenCard(status, YearMonth.of(2027, 1), new BigDecimal("12000"));

        assertDecline(service.authorize(REQUEST, Instant.now()), code, reason);

        verify(events).notify(new AuthServiceAuthDeclinedCardStatusEvent(PAN, status.name()));
        verifyNoInteractions(limits);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void nullStatusFailsClosed() {
        givenCard(null, YearMonth.of(2027, 1), new BigDecimal("12000"));
        assertDecline(service.authorize(REQUEST, Instant.now()), "05", "UNKNOWN_REASON");
        verifyNoInteractions(limits);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void activeCardExpiredByDateIsRejectedBeforeCheckingBalance() {
        givenCard(CardModelStatus.ACTIVE, YearMonth.of(2026, 10), BigDecimal.ZERO);
        assertDecline(service.authorize(REQUEST, Instant.now()), "54", "CARD_EXPIRED");
        verifyNoInteractions(limits);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void oneKopeckAboveBalanceIsRejectedWithoutUpdatingLimits() {
        givenCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), new BigDecimal("2999.99"));
        assertDecline(service.authorize(REQUEST, Instant.now()), "51", "INSUFFICIENT_FUNDS");
        verifyNoInteractions(limits);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void repositoryLimitRejectionPreventsRrnAllocationAndReservation() {
        givenActiveCard();
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY)).thenReturn(0);
        assertDecline(service.authorize(REQUEST, Instant.now()), "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(limits, never()).fetchRrnBlock();
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void duplicateLimitRowIsRetriedOnceBeforeReserving() {
        givenActiveCard();
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY))
                .thenThrow(new DuplicateKeyException("concurrent insertion")).thenReturn(1);

        assertThat(service.authorize(REQUEST, Instant.now()).status()).isEqualTo("APPROVED");

        verify(limits, times(2)).upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY);
        verify(events).notify(isA(AuthServiceDuplicateKeyEvent.class));
        verify(cards).reserve(eq(AMOUNT), anyString(), eq(PAN));
    }

    @Test
    void retryCanReturnLimitExceededInsteadOfApproving() {
        givenActiveCard();
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY))
                .thenThrow(new DuplicateKeyException("concurrent insertion")).thenReturn(0);
        assertDecline(service.authorize(REQUEST, Instant.now()), "61", "EXCEEDS_AMOUNT_LIMIT");
        verify(limits, times(2)).upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void repeatedDuplicateDoesNotRetryIndefinitely() {
        givenActiveCard();
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY))
                .thenThrow(new DuplicateKeyException("concurrent insertion"));
        assertDecline(service.authorize(REQUEST, Instant.now()), "96", "DB_UNAVAILABLE");
        verify(limits, times(2)).upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @Test
    void ordinaryDatabaseFailureIsNotRetried() {
        givenActiveCard();
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY))
                .thenThrow(new IllegalStateException("database unavailable"));
        assertDecline(service.authorize(REQUEST, Instant.now()), "96", "DB_UNAVAILABLE");
        verify(limits).upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    @ParameterizedTest
    @MethodSource("getCardFailures")
    void mapsCardLookupFailuresWithoutFurtherWork(RuntimeException failure, String code, String reason) {
        when(cards.getCard(PAN)).thenThrow(failure);
        assertDecline(service.authorize(REQUEST, Instant.now()), code, reason);
        verifyNoInteractions(limits, binLookup);
        verify(cards, never()).reserve(any(), anyString(), anyString());
    }

    static Stream<Arguments> getCardFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                Arguments.of(new InvalidGetCardRequestException("invalid"), "14", "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("broken"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new GetCardException("unexpected HTTP status"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new PaymentRequiredException("blocked"), "05", "CARD_BLOCKED"),
                Arguments.of(new IllegalStateException("unknown"), "05", "UNKNOWN_REASON"));
    }

    @ParameterizedTest
    @MethodSource("reserveFailures")
    void mapsReservationFailuresAndDoesNotEmitApproval(RuntimeException failure, String code, String reason) {
        givenActiveCard();
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY)).thenReturn(1);
        doThrow(failure).when(cards).reserve(eq(AMOUNT), anyString(), eq(PAN));

        assertDecline(service.authorize(REQUEST, Instant.now()), code, reason);

        verify(cards).reserve(eq(AMOUNT), anyString(), eq(PAN));
        verify(events, never()).notify(isA(AuthServiceAuthorizeEvent.class));
    }

    static Stream<Arguments> reserveFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "14", "CARD_NOT_FOUND"),
                Arguments.of(new InvalidReserveRequestException("invalid"), "14", "CARD_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("broken"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new InsufficientFundsException("balance changed"), "51", "INSUFFICIENT_FUNDS"),
                Arguments.of(new ReserveException("failed"), "96", "RESERVATION_FAILED"),
                Arguments.of(new IllegalStateException("unknown"), "05", "UNKNOWN_REASON"));
    }

    @Test
    void successfulRollbackForwardsOriginalRequestAndReturnsItsRrn() {
        var request = new RollbackRequest("630900001042", PAN, AMOUNT);
        var result = service.rollback(request, Instant.now());
        assertThat(result.status()).isEqualTo("APPROVED");
        assertThat(result.responseCode()).isEqualTo("00");
        assertThat(result.rrn()).isEqualTo(request.rrn());
        assertThat(result.declineReason()).isNull();
        verify(cards).rollback(request);
        verify(events).notify(new AuthServiceRollbackEvent(PAN));
    }

    @ParameterizedTest
    @MethodSource("rollbackFailures")
    void rollbackFailuresKeepRrnAndHaveSpecificDecline(RuntimeException failure, String code, String reason) {
        var request = new RollbackRequest("630900001042", PAN, AMOUNT);
        doThrow(failure).when(cards).rollback(request);

        var result = service.rollback(request, Instant.now());

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo(code);
        assertThat(result.declineReason()).isEqualTo(reason);
        assertThat(result.rrn()).isEqualTo(request.rrn());
        verify(cards).rollback(request);
        verify(events, never()).notify(isA(AuthServiceRollbackEvent.class));
    }

    static Stream<Arguments> rollbackFailures() {
        return Stream.of(
                Arguments.of(new CardNotFoundException("missing"), "14", "TRANSACTION_NOT_FOUND"),
                Arguments.of(new InvalidRollbackRequestException("invalid"), "14", "TRANSACTION_NOT_FOUND"),
                Arguments.of(new ServiceUnavailableException("offline"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new ResourceAccessException("timeout"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new InternalCardManagerException("broken"), "96", "SERVICE_UNAVAILABLE"),
                Arguments.of(new RollbackConflictException("already rolled back"), "05", "ALREADY_ROLLED_BACK"),
                Arguments.of(new RollbackFailureException("failed"), "96", "ROLLBACK_FAILED"),
                Arguments.of(new IllegalStateException("unknown"), "05", "UNKNOWN_REASON"));
    }

    @ParameterizedTest
    @CsvSource({"0,false", "1,true"})
    void limitUpdateUsesRepositoryDecisionAndOriginalLimits(int rows, boolean expected) {
        var card = card(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), new BigDecimal("12000"));
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY)).thenReturn(rows);
        assertThat(service.checkAndUpdateLimits(card, AMOUNT, DAY)).isEqualTo(expected);
        verify(limits).upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY);
    }

    @Test
    void successfulBinEnrichmentDoesNotReplacePaymentIssuerOrBlockApproval() {
        when(cards.getCard(PAN)).thenReturn(card(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), AMOUNT));
        when(binLookup.getIssuerId(PAN)).thenReturn(Optional.of("LOOKUP_ISSUER"));
        when(limits.upsertLimitUsage(PAN, DAY, AMOUNT, DAILY, MONTHLY)).thenReturn(1);
        assertThat(service.authorize(REQUEST, Instant.now()).status()).isEqualTo("APPROVED");
        assertThat(REQUEST.issuerId()).isEqualTo("ISSUER_A");
        verify(binLookup).getIssuerId(PAN);
        verify(cards).reserve(eq(AMOUNT), anyString(), eq(PAN));
    }

    private void givenActiveCard() {
        givenCard(CardModelStatus.ACTIVE, YearMonth.of(2027, 1), new BigDecimal("12000"));
    }

    private void givenCard(CardModelStatus status, YearMonth expiry, BigDecimal balance) {
        when(cards.getCard(PAN)).thenReturn(card(status, expiry, balance));
        when(binLookup.getIssuerId(PAN)).thenReturn(Optional.empty());
    }

    private static CardModel card(CardModelStatus status, YearMonth expiry, BigDecimal balance) {
        return new CardModel(UUID.fromString("00000000-0000-0000-0000-000000000042"), PAN, "400000",
                "KONSTANTIN KARENIN", expiry, status, "643", DAILY, MONTHLY, balance, "ISSUER_A",
                Instant.parse("2026-01-01T00:00:00Z"));
    }

    private static void assertDecline(AuthorizationResponse response, String code, String reason) {
        assertThat(response.status()).isEqualTo("DECLINED");
        assertThat(response.responseCode()).isEqualTo(code);
        assertThat(response.declineReason()).isEqualTo(reason);
        assertThat(response.stan()).isEqualTo(REQUEST.stan());
        assertThat(response.rrn()).isNull();
        assertThat(response.authCode()).isNull();
    }
}
