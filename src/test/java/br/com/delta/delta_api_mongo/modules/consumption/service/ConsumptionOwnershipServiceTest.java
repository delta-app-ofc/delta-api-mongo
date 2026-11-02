package br.com.delta.delta_api_mongo.modules.consumption.service;
import org.junit.jupiter.api.Test;
import br.com.delta.delta_api_mongo.modules.detection.service.*;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class ConsumptionOwnershipServiceTest {
    @Test void usesOnlyRegistryUsersAndKeepsOneWindowForASharedProperty() {
        var client=mock(DeviceOwnerClient.class); var resolver=mock(DeviceOwnerResolver.class);
        when(client.isConfigured()).thenReturn(true);
        when(resolver.forIngestion("DEV")).thenReturn(Optional.of(new DeviceOwner(9,"RESIDENCIAL",List.of(30,7,30),null)));
        var window=new ConsumptionSummaryDocument();window.setDeviceId("DEV");
        new ConsumptionOwnershipService(client,resolver).enrich(window);
        assertThat(window.getUserId()).isEqualTo(7);
        assertThat(window.getUserIds()).containsExactly(7,30);
        assertThat(window.getPropertyId()).isEqualTo(9);
    }
    @Test void neverInventsAnOwnerWhenTheRegistryIsUnavailable() {
        var client=mock(DeviceOwnerClient.class);var resolver=mock(DeviceOwnerResolver.class);
        var service=new ConsumptionOwnershipService(client,resolver);
        assertThatThrownBy(()->service.enrich(new ConsumptionSummaryDocument()))
            .isInstanceOf(br.com.delta.delta_api_mongo.common.exception.ServiceUnavailableException.class);
        verifyNoInteractions(resolver);
    }
}
