package br.com.delta.delta_api_mongo.modules.detection.controller;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import br.com.delta.delta_api_mongo.modules.alerts.security.UserJwtFilter;
import br.com.delta.delta_api_mongo.modules.detection.service.*;
import br.com.delta.delta_api_mongo.modules.detection.model.DeviceOwner;
import br.com.delta.delta_api_mongo.modules.consumption.repository.ConsumptionSummaryRepository;
import java.time.*;
import java.util.*;
import static org.mockito.Mockito.*;
import static org.assertj.core.api.Assertions.*;
class DeviceDetectionControllerTest {
    @Test void onlyACurrentPropertyMemberCanDeclareOrCancelAVisit() {
        var owners=mock(DeviceOwnerResolver.class);var detector=mock(AnomalyDetector.class);
        Instant now=Instant.parse("2026-11-02T12:00:00Z");
        when(owners.fresh("DEV")).thenReturn(Optional.of(new DeviceOwner(1,"RESIDENCIAL",List.of(7),null)));
        var controller=new DeviceDetectionController(owners,detector,mock(ConsumptionSummaryRepository.class),Clock.fixed(now,ZoneOffset.UTC));
        var request=new MockHttpServletRequest();request.setAttribute(UserJwtFilter.USER_ID,7);
        controller.visit("DEV",new DeviceDetectionController.VisitRequest(now.plusSeconds(3600)),request);
        verify(detector).declareVisit("DEV",now.plusSeconds(3600),7,now);
        controller.visit("DEV",new DeviceDetectionController.VisitRequest(null),request);
        verify(detector).declareVisit("DEV",null,7,now);
        request.setAttribute(UserJwtFilter.USER_ID,99);
        assertThatThrownBy(()->controller.visit("DEV",new DeviceDetectionController.VisitRequest(null),request))
            .isInstanceOf(br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException.class);
    }
}
