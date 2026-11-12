package com.processing.cardmanagement.services;

import com.processing.cardmanagement.events.CardEventNotifier;
import com.processing.cardmanagement.events.CardServiceCreationEvent;
import com.processing.cardmanagement.events.CardServicePatchEvent;
import com.processing.cardmanagement.events.CardServiceReserveEvent;
import com.processing.cardmanagement.events.CardServiceRollbackEvent;
import com.processing.cardmanagement.exceptions.CardNotFoundException;
import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.MassiveCardCreationCollisionException;
import com.processing.cardmanagement.exceptions.ReservationAlreadyExistsException;
import com.processing.cardmanagement.exceptions.ReservationNotFoundException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import com.processing.cardmanagement.exceptions.TooLargeLimitException;
import com.processing.cardmanagement.models.Card;
import com.processing.cardmanagement.models.CardDraft;
import com.processing.cardmanagement.models.CardStatus;
import com.processing.cardmanagement.models.Reservation;
import com.processing.cardmanagement.models.ReservationStatus;
import com.processing.cardmanagement.options.CardServiceDefaults;
import com.processing.cardmanagement.options.CardServiceSettings;
import com.processing.cardmanagement.repositories.CardRepository;
import com.processing.cardmanagement.repositories.ReservationRepository;
import com.processing.cardmanagement.repositories.ReservationRollbackRepository;
import com.processing.cardmanagement.services.retries.RetryService;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CardServiceImplTest {
    private static final String PAN = "4000001234567899";
    private static final String RRN = "630900001042";
    private static final BigDecimal AMOUNT = new BigDecimal("3000.00");
    @Mock private CardRepository cards;
    @Mock private ReservationRepository reservations;
    @Mock private ReservationRollbackRepository rollbacks;
    @Mock private CardServiceSettings settings;
    @Mock private CardServiceDefaults defaults;
    @Mock private PanGenerator pans;
    @Mock private CardEventNotifier events;
    @Mock private BinIssuerService issuers;
    @Mock private RetryService retries;
    private CardServiceImpl service;
    private final Card original = new Card(UUID.fromString("00000000-0000-0000-0000-000000000042"), PAN,
            "400000", "KONSTANTIN KARENIN", YearMonth.of(2029, 11), CardStatus.ACTIVE, "643",
            new BigDecimal("15000"), new BigDecimal("60000"), new BigDecimal("12000.00"), "ISSUER_A",
            Instant.parse("2026-11-05T09:00:00Z"));

    @BeforeEach
    void setUp() {
        service = new CardServiceImpl(cards, reservations, rollbacks, settings, defaults, pans, events,
                issuers, retries, new InlineTransactions());
    }

    @Test
    void reserveSavesReservationThenBalanceThenEvent() {
        givenLockedCard(original);
        givenCardWrites();
        when(reservations.isUnique(RRN, PAN)).thenReturn(true);
        when(reservations.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var changed = service.reserve(PAN, AMOUNT, RRN);

        assertThat(changed.availableBalance()).isEqualByComparingTo("9000");
        assertThat(original.availableBalance()).isEqualByComparingTo("12000");
        var capture = ArgumentCaptor.forClass(Reservation.class);
        var order = inOrder(cards, reservations, events);
        order.verify(cards).findByPanForUpdate(PAN);
        order.verify(reservations).isUnique(RRN, PAN);
        order.verify(reservations).save(capture.capture());
        order.verify(cards).update(changed);
        order.verify(events).notifyListeners(new CardServiceReserveEvent(PAN, RRN, AMOUNT));
        assertThat(capture.getValue().reservationAmount()).isEqualByComparingTo(AMOUNT);
        assertThat(capture.getValue().pan()).isEqualTo(PAN);
        assertThat(capture.getValue().rrn()).isEqualTo(RRN);
        assertThat(capture.getValue().status()).isEqualTo(ReservationStatus.RESERVED);
        verifyNoInteractions(rollbacks);
    }

    @Test
    void duplicateReservationCannotChangeBalanceOrEmitSuccess() {
        givenLockedCard(original);
        when(reservations.isUnique(RRN, PAN)).thenReturn(false);
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN))
                .isInstanceOf(ReservationAlreadyExistsException.class);
        verify(reservations, never()).save(any());
        verify(cards, never()).update(any());
        verifyNoInteractions(events, rollbacks);
    }

    @Test
    void insufficientFundsCannotReachReservationRepository() {
        givenLockedCard(original);
        assertThatThrownBy(() -> service.reserve(PAN, new BigDecimal("12000.01"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
        verifyNoInteractions(reservations, rollbacks, events);
        verify(cards, never()).update(any());
    }

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void inactiveStatusPreventsWrites(CardStatus status) {
        givenLockedCard(original.withData(status, original.dailyLimit(), original.monthlyLimit(),
                original.availableBalance()));
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(IllegalStateException.class);
        verifyNoInteractions(reservations, rollbacks, events);
        verify(cards, never()).update(any());
    }

    @Test
    void missingCardPreventsReserve() {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.reserve(PAN, AMOUNT, RRN)).isInstanceOf(CardNotFoundException.class);
        verifyNoInteractions(reservations, rollbacks, events);
        verify(cards, never()).update(any());
    }

    @Test
    void rollbackRestoresBalanceAndStoresCompletedReservation() {
        var reservation = original.startReservation(AMOUNT, RRN);
        givenLockedCard(original.withReservation(reservation));
        givenCardWrites();
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(reservation));
        when(rollbacks.save(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var restored = service.rollback(PAN, AMOUNT, RRN);

        assertThat(restored).isEqualTo(original);
        var completed = ArgumentCaptor.forClass(Reservation.class);
        verify(reservations).save(completed.capture());
        assertThat(completed.getValue().id()).isEqualTo(reservation.id());
        assertThat(completed.getValue().status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        verify(events).notifyListeners(new CardServiceRollbackEvent(PAN, RRN, AMOUNT));
    }

    @Test
    void unknownReservationCannotCreditMoney() {
        givenLockedCard(original);
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.empty());
        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN)).isInstanceOf(ReservationNotFoundException.class);
        verifyNoInteractions(rollbacks, events);
        verify(reservations, never()).save(any());
        verify(cards, never()).update(any());
    }

    @Test
    void alreadyCompletedRollbackCannotCreditMoneyTwice() {
        var reserved = original.startReservation(AMOUNT, RRN);
        var completed = reserved.rolledBack(reserved.startRollback(AMOUNT));
        givenLockedCard(original);
        when(reservations.findByRrnAndPanForUpdate(RRN, PAN)).thenReturn(Optional.of(completed));
        assertThatThrownBy(() -> service.rollback(PAN, AMOUNT, RRN))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
        verifyNoInteractions(rollbacks, events);
        verify(reservations, never()).save(any());
        verify(cards, never()).update(any());
    }

    @ParameterizedTest
    @CsvSource(value = {"KEEP,KEEP,KEEP,KEEP", "BLOCKED,KEEP,KEEP,KEEP", "KEEP,0,0,0",
            "INACTIVE,100,1000,25.50"}, nullValues = "KEEP")
    void patchPreservesOmittedFieldsAndAcceptsExplicitZero(CardStatus status, BigDecimal daily,
                                                         BigDecimal monthly, BigDecimal balance) {
        givenLockedCard(original);
        givenCardWrites();

        var patched = service.patchCard(PAN, status, daily, monthly, balance);

        assertThat(patched.status()).isEqualTo(status == null ? CardStatus.ACTIVE : status);
        assertThat(patched.dailyLimit()).isEqualByComparingTo(daily == null ? original.dailyLimit() : daily);
        assertThat(patched.monthlyLimit()).isEqualByComparingTo(monthly == null ? original.monthlyLimit() : monthly);
        assertThat(patched.availableBalance()).isEqualByComparingTo(balance == null ? original.availableBalance() : balance);
        assertThat(patched.id()).isEqualTo(original.id());
        verify(cards).update(patched);
        verify(events).notifyListeners(new CardServicePatchEvent(PAN));
    }

    @Test
    void invalidPatchIsRejectedBeforeSaving() {
        givenLockedCard(original);
        assertThatThrownBy(() -> service.patchCard(PAN, null, new BigDecimal("60000.01"), null, null))
                .isInstanceOf(IllegalArgumentException.class);
        verify(cards, never()).update(any());
        verifyNoInteractions(events);
    }

    @Test
    void paginationUsesConfiguredDefaultsWhenNotSpecified() {
        when(defaults.pageLimit()).thenReturn(25);
        when(defaults.pageOffset()).thenReturn(0L);
        when(cards.findCards(25, 0L, null, null, null, null, null)).thenReturn(List.of(original));
        assertThat(service.getCards(null, null, null, null, null, null, null)).containsExactly(original);
        verify(cards).findCards(25, 0L, null, null, null, null, null);
    }

    @Test
    void maximumPageSizeAndFiltersAreForwarded() {
        when(settings.maxPageLimit()).thenReturn(100);
        var start = Instant.parse("2026-01-01T00:00:00Z");
        var end = Instant.parse("2027-01-01T00:00:00Z");
        when(cards.findCards(100, 20L, CardStatus.ACTIVE, "400000", "ISSUER_A", start, end))
                .thenReturn(List.of(original));
        assertThat(service.getCards(100, 20L, CardStatus.ACTIVE, "400000", "ISSUER_A", start, end))
                .containsExactly(original);
        verifyNoInteractions(defaults);
    }

    @Test
    void pageLargerThanMaximumDoesNotQueryRepository() {
        when(settings.maxPageLimit()).thenReturn(100);
        assertThatThrownBy(() -> service.getCards(101, null, null, null, null, null, null))
                .isInstanceOf(TooLargeLimitException.class);
        verifyNoInteractions(cards, defaults);
    }

    @Test
    void getCardReturnsRepositoryResultOrSpecificMissingCardError() {
        when(cards.findByPan(PAN)).thenReturn(Optional.of(original), Optional.empty());
        assertThat(service.getCard(PAN)).isSameAs(original);
        assertThatThrownBy(() -> service.getCard(PAN)).isInstanceOf(CardNotFoundException.class);
    }

    @Test
    void bulkStatusUpdateRequiresExactlyOneSelector() {
        assertThatThrownBy(() -> service.bulkUpdateStatus(null, null, CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("must be provided");
        assertThatThrownBy(() -> service.bulkUpdateStatus(List.of("400000"), List.of(PAN), CardStatus.BLOCKED))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("Only one");
        verifyNoInteractions(cards);
    }

    @ParameterizedTest
    @ValueSource(booleans = {true, false})
    void bulkUpdateSelectsByBinOrPanAndPreservesFinancialFields(boolean byBin) {
        if (byBin) {
            when(cards.findCardsByBinsForUpdate(List.of("400000"))).thenReturn(List.of(original));
        } else {
            when(cards.findCardsByPansForUpdate(List.of(PAN))).thenReturn(List.of(original));
        }
        int changed = service.bulkUpdateStatus(byBin ? List.of("400000") : null,
                byBin ? null : List.of(PAN), CardStatus.BLOCKED);
        assertThat(changed).isEqualTo(1);
        var updated = ArgumentCaptor.forClass(Card.class);
        verify(cards).update(updated.capture());
        assertThat(updated.getValue().status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(updated.getValue().availableBalance()).isEqualByComparingTo(original.availableBalance());
        assertThat(updated.getValue().dailyLimit()).isEqualByComparingTo(original.dailyLimit());
        assertThat(updated.getValue().monthlyLimit()).isEqualByComparingTo(original.monthlyLimit());
        if (byBin) {
            verify(cards, never()).findCardsByPansForUpdate(any());
        } else {
            verify(cards, never()).findCardsByBinsForUpdate(any());
        }
    }

    @Test
    void singleCreationBuildsActiveCardWithIssuerAndEmitsCount() {
        givenCreationDependencies();
        when(cards.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createCard("400000", "KONSTANTIN KARENIN", "643", original.dailyLimit(),
                original.monthlyLimit(), original.availableBalance());

        assertThat(created.pan()).isEqualTo(PAN);
        assertThat(created.bin()).isEqualTo("400000");
        assertThat(created.status()).isEqualTo(CardStatus.ACTIVE);
        assertThat(created.issuerId()).isEqualTo("ISSUER_A");
        assertThat(created.cardholderName()).isEqualTo("KONSTANTIN KARENIN");
        assertThat(created.availableBalance()).isEqualByComparingTo("12000");
        verify(retries).supply(eq(2), any());
        verify(events).notifyListeners(new CardServiceCreationEvent(1));
    }

    @Test
    void batchCollisionFallsBackToRetriedIndividualCreation() {
        givenCreationDependencies();
        when(pans.generatePan("400000"))
                .thenReturn(PAN, "4000000000000010", "4000000000000028", "4000000000000036");
        when(cards.createAll(any())).thenThrow(new MassiveCardCreationCollisionException());
        when(cards.create(any())).thenAnswer(invocation -> invocation.getArgument(0));

        var created = service.createCards(List.of(draft(), draft()));

        assertThat(created).hasSize(2).allSatisfy(card -> {
            assertThat(card.issuerId()).isEqualTo("ISSUER_A");
            assertThat(card.availableBalance()).isEqualByComparingTo("12000");
        });
        assertThat(created).extracting(Card::pan).containsExactly("4000000000000028", "4000000000000036");
        verify(cards).createAll(any());
        verify(cards, times(2)).create(any());
        verify(retries, times(2)).supply(eq(2), any());
        verify(events).notifyListeners(new CardServiceCreationEvent(2));
    }

    @Test
    void successfulBatchDoesNotUseIndividualRetries() {
        when(pans.generatePan("400000")).thenReturn(PAN);
        when(issuers.getIssuerId("400000")).thenReturn("ISSUER_A");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(cards.createAll(any())).thenReturn(List.of(original));

        assertThat(service.createCards(List.of(draft()))).containsExactly(original);

        verifyNoInteractions(retries);
        verify(cards, never()).create(any());
        verify(events).notifyListeners(new CardServiceCreationEvent(1));
    }

    private CardDraft draft() {
        return new CardDraft("400000", "KONSTANTIN KARENIN", CardStatus.ACTIVE, "643",
                original.dailyLimit(), original.monthlyLimit(), original.availableBalance());
    }

    private void givenCreationDependencies() {
        when(pans.generatePan("400000")).thenReturn(PAN);
        when(issuers.getIssuerId("400000")).thenReturn("ISSUER_A");
        when(settings.cardValidityPeriod()).thenReturn(3);
        when(settings.maxCardCreationRetries()).thenReturn(2);
        when(retries.supply(eq(2), any())).thenAnswer(invocation -> invocation.<Supplier<?>>getArgument(1).get());
    }

    private void givenLockedCard(Card card) {
        when(cards.findByPanForUpdate(PAN)).thenReturn(Optional.of(card));
    }

    private void givenCardWrites() {
        when(cards.update(any())).thenAnswer(invocation -> invocation.getArgument(0));
    }

    // Executes callbacks only; database commit, isolation and rollback are integration-test concerns.
    private static final class InlineTransactions implements TransactionRunner {
        @Override
        public void run(Runnable operation) {
            operation.run();
        }

        @Override
        public <T> T runSupplier(Supplier<T> operation) {
            return operation.get();
        }

        @Override
        public <T> T runSupplierWithNestedQuery(Function<TransactionRunner, T> operation) {
            return operation.apply(this);
        }
    }
}
