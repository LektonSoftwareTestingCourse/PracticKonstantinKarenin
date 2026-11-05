package com.processing.service;

import com.processing.common.dto.authorization.AuthorizationRequest;
import com.processing.common.dto.authorization.AuthorizationResponse;
import com.processing.common.dto.authorization.RollbackResponse;
import com.processing.common.dto.transactionlogger.TransactionRequest;
import com.processing.common.dto.transactionlogger.TransactionStatus;
import com.processing.exception.AuthorizationException;
import com.processing.exception.UnknownBinException;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class RouteServiceTest {
    private static final String RRN = "630900001042";
    private static final BigDecimal AMOUNT = new BigDecimal("3000.00");
    private static final AuthorizationRequest REQUEST = new AuthorizationRequest(
            "0100", "004201", "4000001234567899", "000000", AMOUNT, "643",
            Instant.parse("2026-11-05T09:00:00Z"), "T42", "POS", "SHOP42", "5411", "ACQ042", null);
    @Mock private RoutingService routing;
    @Mock private AuthorizationClient authorization;
    @Mock private AcquiringFeeClient fees;
    @Mock private LoggerClient logger;
    private RouteService service;

    @BeforeEach
    void setUp() {
        service = new RouteService(routing, authorization, fees, logger);
    }

    @Test
    void approvedPaymentIsRoutedNormalizedAndPublishedWithFee() {
        var approved = response("APPROVED", "00", null, 17);
        stubAuthorization(approved);
        when(fees.fetchAcquiringFee(REQUEST.transmissionDateTime(), REQUEST.stan(), REQUEST.pan(),
                "T4200000", AMOUNT)).thenReturn(new BigDecimal("45.00"));
        when(logger.log(any())).thenReturn(true);

        assertThat(service.route(REQUEST)).isSameAs(approved);

        var forwarded = ArgumentCaptor.forClass(AuthorizationRequest.class);
        verify(authorization).authorize(forwarded.capture());
        assertThat(forwarded.getValue().issuerId()).isEqualTo("ISSUER_A");
        assertThat(forwarded.getValue().terminalId()).isEqualTo("T4200000");
        assertThat(forwarded.getValue().merchantId()).isEqualTo("SHOP42000000000");
        assertThat(forwarded.getValue().amount()).isEqualByComparingTo(AMOUNT);
        assertThat(REQUEST.issuerId()).isNull();
        var published = publishedTransaction();
        assertThat(published.id()).isNotNull();
        assertThat(published.pan()).isEqualTo(REQUEST.pan());
        assertThat(published.stan()).isEqualTo(REQUEST.stan());
        assertThat(published.rrn()).isEqualTo(RRN);
        assertThat(published.authCode()).isEqualTo("AUTH42");
        assertThat(published.status()).isEqualTo(TransactionStatus.APPROVED);
        assertThat(published.amount()).isEqualByComparingTo(AMOUNT);
        assertThat(published.acquiringFee()).isEqualByComparingTo("45.00");
        assertThat(published.issuerId()).isEqualTo("ISSUER_A");
        assertThat(published.processingTimeMs()).isEqualTo(17);
        verify(authorization, never()).rollback(any(), any());
    }

    @ParameterizedTest
    @MethodSource("rollbackOutcomes")
    void publicationFailureCompensatesApprovedPaymentOnce(RollbackResponse rollback) {
        stubAuthorization(response("APPROVED", "00", null, 17));
        when(logger.log(any())).thenReturn(false);
        when(authorization.rollback(any(), eq(RRN))).thenReturn(rollback);

        var result = service.route(REQUEST);

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("96");
        assertThat(result.stan()).isEqualTo(REQUEST.stan());
        assertThat(result.authCode()).isNull();
        var routed = AuthorizationRequestNormalizer.normalize(REQUEST).withIssuerId("ISSUER_A");
        var order = inOrder(logger, authorization);
        order.verify(authorization).authorize(routed);
        order.verify(logger).log(any());
        order.verify(authorization).rollback(routed, RRN);
        order.verifyNoMoreInteractions();
    }

    static Stream<RollbackResponse> rollbackOutcomes() {
        return Stream.of(null, new RollbackResponse(RRN, "00", "APPROVED", null, 1),
                new RollbackResponse(RRN, "96", "DECLINED", "SERVICE_UNAVAILABLE", 1));
    }

    @Test
    void declinedPaymentIsNeverCompensatedEvenIfPublicationFails() {
        var declined = response("DECLINED", "51", "INSUFFICIENT_FUNDS", null);
        stubAuthorization(declined);
        when(logger.log(any())).thenReturn(false);

        assertThat(service.route(REQUEST)).isSameAs(declined);

        assertThat(publishedTransaction().status()).isEqualTo(TransactionStatus.DECLINED);
        assertThat(publishedTransaction().declineReason()).isEqualTo("INSUFFICIENT_FUNDS");
        assertThat(publishedTransaction().processingTimeMs()).isZero();
        verify(authorization, never()).rollback(any(), any());
    }

    @Test
    void unknownBinIsDeclinedWithoutCallingAuthorizationOrFeeService() {
        when(routing.getIssuerIdByPan(REQUEST.pan())).thenThrow(new UnknownBinException("400000"));

        var result = service.route(REQUEST);

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("14");
        assertThat(result.declineReason()).isEqualTo("CARD_NOT_FOUND");
        assertThat(publishedTransaction().issuerId()).isNull();
        assertThat(publishedTransaction().stan()).isEqualTo(REQUEST.stan());
        verifyNoInteractions(authorization, fees);
    }

    @Test
    void unavailableAuthorizationStillPublishesDeclineWithoutCompensation() {
        when(routing.getIssuerIdByPan(REQUEST.pan())).thenReturn("ISSUER_A");
        when(authorization.authorize(any())).thenThrow(new AuthorizationException(REQUEST.stan(), 3, "timeout"));

        var result = service.route(REQUEST);

        assertThat(result.status()).isEqualTo("DECLINED");
        assertThat(result.responseCode()).isEqualTo("05");
        assertThat(result.declineReason()).isEqualTo("Authorization service unavailable");
        assertThat(publishedTransaction().status()).isEqualTo(TransactionStatus.DECLINED);
        verifyNoInteractions(fees);
        verify(authorization, never()).rollback(any(), any());
    }

    private void stubAuthorization(AuthorizationResponse response) {
        when(routing.getIssuerIdByPan(REQUEST.pan())).thenReturn("ISSUER_A");
        when(authorization.authorize(any())).thenReturn(response);
    }

    private TransactionRequest publishedTransaction() {
        var capture = ArgumentCaptor.forClass(TransactionRequest.class);
        verify(logger).log(capture.capture());
        return capture.getValue();
    }

    private static AuthorizationResponse response(String status, String code, String reason, Integer elapsed) {
        return new AuthorizationResponse("0110", REQUEST.stan(), RRN, "AUTH42", code, status, reason, elapsed);
    }
}
