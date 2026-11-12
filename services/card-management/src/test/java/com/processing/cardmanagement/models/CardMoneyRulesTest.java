package com.processing.cardmanagement.models;

import com.processing.cardmanagement.exceptions.InsufficientFundsException;
import com.processing.cardmanagement.exceptions.RollbackAlreadySatisfiedException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.EnumSource;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.YearMonth;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class CardMoneyRulesTest {
    private static final String PAN = "4000001234567899";
    private static final String RRN = "630900001042";
    private final Card original = new Card(UUID.fromString("00000000-0000-0000-0000-000000000042"),
            PAN, "400000", "KONSTANTIN KARENIN", YearMonth.of(2029, 11), CardStatus.ACTIVE, "643",
            new BigDecimal("15000"), new BigDecimal("60000"), new BigDecimal("12000.00"), "ISSUER_A",
            Instant.parse("2026-11-05T09:00:00Z"));

    @ParameterizedTest
    @EnumSource(value = CardStatus.class, names = "ACTIVE", mode = EnumSource.Mode.EXCLUDE)
    void nonActiveCardsCannotStartReservation(CardStatus status) {
        var card = original.withData(status, original.dailyLimit(), original.monthlyLimit(), original.availableBalance());
        assertThatThrownBy(() -> card.startReservation(new BigDecimal("3000"), RRN))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining(status.name());
        assertThat(card.availableBalance()).isEqualByComparingTo("12000");
    }

    @ParameterizedTest
    @CsvSource({"0.01,11999.99", "11999.99,0.01", "12000.00,0.00"})
    void reservationSubtractsExactAmountWithoutMutatingSource(String amount, String remaining) {
        var reservation = original.startReservation(new BigDecimal(amount), RRN);
        var changed = original.withReservation(reservation);

        assertThat(reservation.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThat(reservation.pan()).isEqualTo(PAN);
        assertThat(reservation.rrn()).isEqualTo(RRN);
        assertThat(reservation.reservationAmount()).isEqualByComparingTo(amount);
        assertThat(changed.availableBalance()).isEqualByComparingTo(remaining);
        assertThat(changed.id()).isEqualTo(original.id());
        assertThat(changed.createdAt()).isEqualTo(original.createdAt());
        assertThat(original.availableBalance()).isEqualByComparingTo("12000");
    }

    @Test
    void oneKopeckOverBalanceIsRejected() {
        assertThatThrownBy(() -> original.startReservation(new BigDecimal("12000.01"), RRN))
                .isInstanceOf(InsufficientFundsException.class);
        assertThat(original.availableBalance()).isEqualByComparingTo("12000");
    }

    @Test
    void fullRollbackRestoresMoneyAndCannotBeStartedTwice() {
        var reserved = original.startReservation(new BigDecimal("3000"), RRN);
        var charged = original.withReservation(reserved);
        var rollback = reserved.startRollback(new BigDecimal("3000"));
        var completed = reserved.rolledBack(rollback);
        var restored = charged.withRollback(rollback);

        assertThat(charged.availableBalance()).isEqualByComparingTo("9000");
        assertThat(restored).isEqualTo(original);
        assertThat(rollback.reservationId()).isEqualTo(reserved.id());
        assertThat(rollback.pan()).isEqualTo(PAN);
        assertThat(rollback.rrn()).isEqualTo(RRN);
        assertThat(completed.id()).isEqualTo(reserved.id());
        assertThat(completed.reservationAmount()).isEqualByComparingTo("3000");
        assertThat(completed.status()).isEqualTo(ReservationStatus.ROLLED_BACK);
        assertThat(reserved.status()).isEqualTo(ReservationStatus.RESERVED);
        assertThatThrownBy(() -> completed.startRollback(new BigDecimal("3000")))
                .isInstanceOf(RollbackAlreadySatisfiedException.class);
    }

    @Test
    void reservationAndRollbackMustBelongToTheirOwner() {
        var foreign = new Reservation("4000011234567890", new BigDecimal("3000"), RRN);
        assertThatThrownBy(() -> original.withReservation(foreign))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PAN");
        assertThatThrownBy(() -> original.withRollback(foreign.startRollback(new BigDecimal("3000"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("PAN");
        var own = original.startReservation(new BigDecimal("3000"), RRN);
        assertThatThrownBy(() -> own.rolledBack(foreign.startRollback(new BigDecimal("3000"))))
                .isInstanceOf(IllegalArgumentException.class).hasMessageContaining("reservation ID");
    }

    @ParameterizedTest
    @CsvSource({"-0.01,60000", "15000,-0.01", "60000.01,60000"})
    void rejectsNegativeLimitsAndDailyLimitAboveMonthly(String daily, String monthly) {
        assertThatThrownBy(() -> original.withData(CardStatus.ACTIVE, new BigDecimal(daily),
                new BigDecimal(monthly), original.availableBalance())).isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest
    @CsvSource({"0,0", "0,0.01", "59999.99,60000", "60000,60000"})
    void acceptsZeroLimitsAndDailyLimitEqualToMonthly(String daily, String monthly) {
        var changed = original.withData(CardStatus.BLOCKED, new BigDecimal(daily), new BigDecimal(monthly),
                BigDecimal.ZERO);
        assertThat(changed.dailyLimit()).isEqualByComparingTo(daily);
        assertThat(changed.monthlyLimit()).isEqualByComparingTo(monthly);
        assertThat(changed.availableBalance()).isZero();
        assertThat(changed.status()).isEqualTo(CardStatus.BLOCKED);
        assertThat(original.status()).isEqualTo(CardStatus.ACTIVE);
    }

    @Test
    void deletionIsAnImmutableOneWayTransition() {
        var deleted = original.deleted();
        assertThat(deleted.status()).isEqualTo(CardStatus.DELETED);
        assertThat(deleted.id()).isEqualTo(original.id());
        assertThat(deleted.availableBalance()).isEqualByComparingTo(original.availableBalance());
        assertThat(original.status()).isEqualTo(CardStatus.ACTIVE);
        assertThatThrownBy(deleted::deleted).isInstanceOf(IllegalStateException.class).hasMessage("Card already deleted");
    }
}
