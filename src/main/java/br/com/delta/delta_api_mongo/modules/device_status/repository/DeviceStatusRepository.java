package br.com.delta.delta_api_mongo.modules.device_status.repository;

import br.com.delta.delta_api_mongo.modules.device_status.document.DeviceStatusDocument;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Repository;
import java.util.Optional;

@Repository
public class DeviceStatusRepository {
    private final MongoTemplate mongo;

    public DeviceStatusRepository(@Qualifier("telemetryMongoTemplate") MongoTemplate mongo) {
        this.mongo = mongo;
    }

    public Optional<DeviceStatusDocument> findByDeviceId(String deviceId) {
        return Optional.ofNullable(mongo.findOne(Query.query(Criteria.where("device_id").is(deviceId)),
                DeviceStatusDocument.class));
    }

    public DeviceStatusDocument saveLatest(DeviceStatusDocument document) {
        var query = Query.query(Criteria.where("device_id").is(document.getDeviceId())
                .and("last_ping_at").lte(document.getLastPingAt()));
        var update = new Update().set("device_id", document.getDeviceId())
                .set("last_ping_at", document.getLastPingAt())
                .set("wifi_signal_rssi", document.getWifiSignalRssi())
                .set("firmware_version", document.getFirmwareVersion())
                .set("connectivity_status", document.getConnectivityStatus())
                .set("unavailability_reason", document.getUnavailabilityReason());
        // The unique device index prevents stale upserts and concurrent first pings from creating duplicates.
        try {
            return mongo.findAndModify(query, update, FindAndModifyOptions.options().upsert(true).returnNew(true),
                    DeviceStatusDocument.class);
        } catch (DuplicateKeyException exception) {
            var current = findByDeviceId(document.getDeviceId());
            if (current.isPresent() && !current.get().getLastPingAt().isBefore(document.getLastPingAt())) {
                return current.get();
            }
            // A concurrent insert contained an older ping; apply this newer ping without another insert.
            var updated = mongo.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true),
                    DeviceStatusDocument.class);
            if (updated != null) return updated;
            return findByDeviceId(document.getDeviceId()).orElseThrow(() -> exception);
        }
    }
}
