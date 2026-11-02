package br.com.delta.delta_api_mongo.modules.alerts.controller;

import br.com.delta.delta_api_mongo.modules.alerts.dto.response.AlertResponse;
import br.com.delta.delta_api_mongo.modules.alerts.dto.response.LeakSummaryResponse;
import br.com.delta.delta_api_mongo.modules.alerts.security.UserJwtFilter;
import br.com.delta.delta_api_mongo.modules.alerts.service.AlertsService;
import br.com.delta.delta_api_mongo.modules.alerts.swagger.AlertsSwagger;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.data.web.PagedModel;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestAttribute;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/alerts")
@ConditionalOnProperty(prefix = "delta.alerts", name = "enabled", havingValue = "true")
public class AlertsController implements AlertsSwagger {
    private final AlertsService service;

    public AlertsController(AlertsService service) {
        this.service = service;
    }

    @Override
    @GetMapping
    public PagedModel<AlertResponse> list(
            @RequestParam(name = "severity", required = false) String severity,
            @RequestParam(name = "include_dismissed", defaultValue = "false") boolean includeDismissed,
            @RequestAttribute(UserJwtFilter.USER_ID) Integer userId,
            @PageableDefault(size = 20) Pageable pageable) {
        return service.list(userId, severity, includeDismissed, pageable);
    }

    @Override
    @PatchMapping("/{id}/dismiss")
    public AlertResponse dismiss(@PathVariable("id") String id,
                                 @RequestAttribute(UserJwtFilter.USER_ID) Integer userId) {
        return service.dismiss(userId, id);
    }

    @Override
    @GetMapping("/leak-summary")
    public LeakSummaryResponse leakSummary(@RequestAttribute(UserJwtFilter.USER_ID) Integer userId) {
        return service.leakSummary(userId);
    }
}
