package com.academy.paybridge.transfer.client;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class PaystackStatusMappingTest {

    @Test
    void mapsConclusiveAndInconclusiveStatuses() {
        assertThat(PaystackTransferGateway.mapStatus("success")).isEqualTo(GatewayStatus.SUCCESS);
        assertThat(PaystackTransferGateway.mapStatus("reversed")).isEqualTo(GatewayStatus.FAILED);
        assertThat(PaystackTransferGateway.mapStatus("failed")).isEqualTo(GatewayStatus.FAILED);
        assertThat(PaystackTransferGateway.mapStatus("pending")).isEqualTo(GatewayStatus.PENDING);
        assertThat(PaystackTransferGateway.mapStatus("otp")).isEqualTo(GatewayStatus.PENDING);
        assertThat(PaystackTransferGateway.mapStatus("something-new")).isEqualTo(GatewayStatus.PENDING);
        assertThat(PaystackTransferGateway.mapStatus(null)).isEqualTo(GatewayStatus.PENDING);
    }
}