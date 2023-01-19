package com.badoo.automation.deviceserver.data

import com.fasterxml.jackson.annotation.JsonProperty

data class LocationDto(
    @JsonProperty("latitude")
    val latitude: Double,

    @JsonProperty("longitude")
    val longitude: Double
)

data class LocationScenarioDto(
    @JsonProperty("scenario_name")
    val scenarioName: String
)

data class LocationWaypointsDto(
    @JsonProperty("speed")
    val speed: Int = 0,

    @JsonProperty("distance")
    val distance: Int = 0,

    @JsonProperty("interval")
    val interval: Int = 0,

    @JsonProperty("waypoints")
    val waypoints: List<LocationDto> = listOf()
)
