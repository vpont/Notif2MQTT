package com.notif2mqtt.models

enum class ConnectionState {
    CONNECTING,
    CONNECTED,
    DISCONNECTED,
    WAITING_WIFI,
    ERROR
}