package br.com.delta.delta_api_mongo.modules.device_status.document;

import lombok.AllArgsConstructor;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;
import org.bson.types.ObjectId;
import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.mongodb.core.mapping.Field;
import java.time.Instant;

@Document(collection = "device_status")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
public class DeviceStatusDocument {
    @Id private ObjectId id;
    @Field("device_id") private String deviceId;
    @Field("last_ping_at") private Instant lastPingAt;
    @Field("wifi_signal_rssi") private String wifiSignalRssi;
    @Field("firmware_version") private String firmwareVersion;
    @Field("connectivity_status") private String connectivityStatus;
    @Field("battery_level") private Integer batteryLevel;
    @Field("unavailability_reason") private String unavailabilityReason;
}
