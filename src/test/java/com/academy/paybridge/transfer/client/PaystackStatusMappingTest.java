package com.academy.paybridge.transfer.client;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.assertj.core.api.Assertions.assertThat;

class PaystackStatusMappingTest {

    @Test
    void mapsConclusiveAndInconclusiveStatuses() {
        assertThat(PaystackTransferGateway.mapStatus("success")).isEqualTo(GatewayStatus.SUCCESS);
        assertEquals(GatewayStatus.REVERSED, PaystackTransferGateway.mapStatus("reversed"));
        assertThat(PaystackTransferGateway.mapStatus("failed")).isEqualTo(GatewayStatus.FAILED);
        assertThat(PaystackTransferGateway.mapStatus("pending")).isEqualTo(GatewayStatus.PENDING);
        assertThat(PaystackTransferGateway.mapStatus("otp")).isEqualTo(GatewayStatus.PENDING);
        assertThat(PaystackTransferGateway.mapStatus("something-new")).isEqualTo(GatewayStatus.PENDING);
        assertThat(PaystackTransferGateway.mapStatus(null)).isEqualTo(GatewayStatus.PENDING);
    }
}