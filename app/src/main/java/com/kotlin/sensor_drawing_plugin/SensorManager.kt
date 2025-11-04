package com.kotlin.sensor_drawing_plugin

import android.Manifest
import androidx.annotation.RequiresPermission
import com.kotlin.sensor_drawing_plugin.datasource.LocationDataSource
import kotlinx.coroutines.launch

class SensorManager {
    private var _dataSource: LocationDataSource? = null

    @RequiresPermission(allOf = [Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION])
    fun startActivity() {
        //todo
        ServiceLocator.scope.launch {
            _dataSource?.start()
        }
    }

    fun stopActivity() {
        _dataSource = null
        //todo
        ServiceLocator.scope.launch {
            _dataSource?.stop()
        }
    }

    fun setSensor(sensor: LocationDataSource) {
        _dataSource = sensor
    }
}