package br.com.delta.delta_api_mongo.modules.consumption.service;

import br.com.delta.delta_api_mongo.common.exception.ResourceNotFoundException;
import br.com.delta.delta_api_mongo.common.exception.ServiceUnavailableException;
import br.com.delta.delta_api_mongo.modules.consumption.document.ConsumptionSummaryDocument;
import br.com.delta.delta_api_mongo.modules.detection.service.DeviceOwnerClient;
import br.com.delta.delta_api_mongo.modules.detection.service.DeviceOwnerResolver;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;

/** Associação cadastral obtida do SQL; uma janela nunca é duplicada para atender um imóvel compartilhado. */
@Service
@RequiredArgsConstructor
public class ConsumptionOwnershipService {
    private final DeviceOwnerClient client;
    private final DeviceOwnerResolver owners;

    public void enrich(ConsumptionSummaryDocument window) {
        if (!client.isConfigured()) throw new ServiceUnavailableException("Consulta de cadastro não configurada.");
        var owner = owners.fresh(window.getDeviceId()).orElseThrow(() ->
                new ResourceNotFoundException("Dispositivo nao associado ao cadastro."));
        var users = owner.userIds().stream().filter(id -> id != null && id > 0).distinct().sorted().toList();
        if (users.isEmpty() || owner.propertyId() == null) {
            throw new ResourceNotFoundException("Dispositivo sem imóvel ou usuários vinculados.");
        }
        window.setUserId(users.get(0));
        window.setUserIds(users);
        window.setPropertyId(owner.propertyId());
    }
}
