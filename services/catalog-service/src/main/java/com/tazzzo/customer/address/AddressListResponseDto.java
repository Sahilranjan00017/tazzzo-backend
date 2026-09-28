package com.tazzzo.customer.address;

import java.util.List;

public record AddressListResponseDto(List<AddressResponseDto> items, String requestId) {
}
