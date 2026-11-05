package com.processing.authorization.services;

import com.processing.authorization.client.BinLookupClient;
import com.processing.authorization.client.CardManagementClient;
import com.processing.authorization.events.AuthorizationEventNotifier;
import com.processing.authorization.repositories.LimitUsageRepository;
import org.junit.jupiter.api.Test;

import java.util.HashSet;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class RrnAllocationTest {
    @Test
    void nextBlockIsFetchedOnlyWhenOneThousandIdentifiersAreConsumed() {
        var repository = mock(LimitUsageRepository.class);
        when(repository.fetchRrnBlock()).thenReturn(42L, 43L);
        var service = new AuthServiceImpl(repository, mock(AuthorizationEventNotifier.class),
                mock(BinLookupClient.class), mock(CardManagementClient.class));
        var allocated = new HashSet<String>();

        for (int index = 0; index < 1000; index++) {
            String rrn = service.generateRRN();
            assertThat(rrn).matches("\\d{12}");
            assertThat(rrn.substring(4)).isEqualTo(String.format("%08d", 42000 + index));
            assertThat(allocated.add(rrn)).isTrue();
        }
        verify(repository).fetchRrnBlock();

        String firstFromNextBlock = service.generateRRN();

        assertThat(firstFromNextBlock.substring(4)).isEqualTo("00043000");
        assertThat(allocated.add(firstFromNextBlock)).isTrue();
        assertThat(allocated).hasSize(1001);
        verify(repository, times(2)).fetchRrnBlock();
    }
}
