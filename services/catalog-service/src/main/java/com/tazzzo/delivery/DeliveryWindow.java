package com.tazzzo.delivery;

/** A stored window: its definition plus lifecycle state and CAS version. */
public record DeliveryWindow(String serviceAreaId, SlotWindow window, boolean active, long version) { }
