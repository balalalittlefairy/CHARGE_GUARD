package io.github.chargercontroller.network

import io.github.chargercontroller.data.PlugDevice
import io.github.chargercontroller.data.PlugStatus

interface PlugClient {
    fun read(device: PlugDevice): PlugStatus
    fun setPower(device: PlugDevice, on: Boolean): PlugStatus
}
