package com.matelink.data.repository

import com.matelink.data.api.models.BatteryHealth
import com.matelink.data.api.models.CarData
import com.matelink.data.api.models.CarDetails
import com.matelink.data.api.models.CarExterior
import com.matelink.data.api.models.CarGeodata
import com.matelink.data.api.models.CarSettings
import com.matelink.data.api.models.CarStatus
import com.matelink.data.api.models.CarStatusDetails
import com.matelink.data.api.models.CarVersions
import com.matelink.data.api.models.ChargeBatteryDetails
import com.matelink.data.api.models.ChargeData
import com.matelink.data.api.models.ChargeDetail
import com.matelink.data.api.models.ChargePoint
import com.matelink.data.api.models.ChargeRange
import com.matelink.data.api.models.ChargerDetails
import com.matelink.data.api.models.ChargingDetails
import com.matelink.data.api.models.ClimateDetails
import com.matelink.data.api.models.DriveBatteryDetails
import com.matelink.data.api.models.DriveData
import com.matelink.data.api.models.DriveDetail
import com.matelink.data.api.models.DriveOdometerDetails
import com.matelink.data.api.models.DrivePosition
import com.matelink.data.api.models.DriveRange
import com.matelink.data.api.models.DrivingDetails
import com.matelink.data.api.models.GlobalSettingsData
import com.matelink.data.api.models.TeslamateStats
import com.matelink.data.api.models.Units
import com.matelink.data.api.models.UpdateData
import java.time.Instant
import java.time.format.DateTimeFormatter

/**
 * Provides hardcoded sample data for mock mode.
 * All data is realistic and self-consistent for demo/testing purposes.
 */
object MockDataProvider {

    private fun isoNow(): String = DateTimeFormatter.ISO_INSTANT.format(Instant.now())
    private fun isoPast(minutesAgo: Int): String =
        DateTimeFormatter.ISO_INSTANT.format(Instant.now().minusSeconds(minutesAgo * 60L))

    fun getCars(): List<CarData> = listOf(
        CarData(
            carId = 1,
            name = "Model 3 Performance",
            carDetails = CarDetails(
                model = "3",
                trimBadging = "P74D",
                vin = "5YJ3E1EA1LF000001",
                efficiency = 153.0
            ),
            carExterior = CarExterior(
                exteriorColor = "Red",
                wheelType = "WY18P",
                spoilerType = "Carbon"
            ),
            carSettings = CarSettings(freeSupercharging = false),
            teslamateStats = TeslamateStats(totalCharges = 247, totalDrives = 892)
        )
    )

    fun getCarStatus(): CarStatus = CarStatus(
        displayName = "Model 3 Performance",
        state = "online",
        stateSince = isoPast(5),
        odometer = 42350.7,
        carStatus = CarStatusDetails(
            healthy = true,
            locked = true,
            sentryMode = true,
            windowsOpen = false,
            doorsOpen = false,
            trunkOpen = false,
            frunkOpen = false,
            isUserPresent = false,
            centerDisplayState = "0"
        ),
        carGeodata = CarGeodata(
            geofence = "Home",
            latitude = 52.5200,
            longitude = 13.4050
        ),
        carVersions = CarVersions(
            version = "2024.38.7",
            updateAvailable = false
        ),
        drivingDetails = DrivingDetails(
            shiftState = null,
            speed = null,
            power = null,
            heading = null,
            elevation = 85
        ),
        climateDetails = ClimateDetails(
            isClimateOn = false,
            insideTemp = 21.5,
            outsideTemp = 14.2
        ),
        batteryDetails = com.matelink.data.api.models.BatteryDetails(
            batteryLevel = 72,
            usableBatteryLevel = 70,
            estBatteryRange = 310.0,
            ratedBatteryRange = 295.0,
            idealBatteryRange = 340.0
        ),
        chargingDetails = ChargingDetails(
            pluggedIn = true,
            chargingState = "stopped",
            chargeEnergyAdded = 12.5,
            chargeLimitSoc = 80,
            chargerPower = null,
            chargerActualCurrent = null,
            timeToFullCharge = null
        ),
        tpmsDetails = null
    )

    fun getCharges(): List<ChargeData> = listOf(
        ChargeData(
            chargeId = 1001,
            startDate = isoPast(1440),
            endDate = isoPast(1380),
            address = "Supercharger Berlin",
            chargeEnergyAdded = 45.2,
            chargeEnergyUsed = 48.1,
            cost = 0.0,
            durationMin = 60,
            durationStr = "1h 0m",
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 20, endBatteryLevel = 80),
            rangeIdeal = ChargeRange(startRange = 120.0, endRange = 410.0),
            rangeRated = ChargeRange(startRange = 95.0, endRange = 320.0),
            outsideTempAvg = 18.5,
            odometer = 42000.0,
            latitude = 52.4500,
            longitude = 13.4000
        ),
        ChargeData(
            chargeId = 1002,
            startDate = isoPast(2880),
            endDate = isoPast(2820),
            address = "Home Wallbox",
            chargeEnergyAdded = 22.8,
            chargeEnergyUsed = 24.0,
            cost = 8.50,
            durationMin = 180,
            durationStr = "3h 0m",
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 45, endBatteryLevel = 80),
            rangeIdeal = ChargeRange(startRange = 270.0, endRange = 410.0),
            rangeRated = ChargeRange(startRange = 210.0, endRange = 320.0),
            outsideTempAvg = 12.0,
            odometer = 41500.0,
            latitude = 52.5200,
            longitude = 13.4050
        ),
        ChargeData(
            chargeId = 1003,
            startDate = isoPast(4320),
            endDate = isoPast(4260),
            address = "Supercharger Potsdam",
            chargeEnergyAdded = 38.5,
            chargeEnergyUsed = 41.0,
            cost = 0.0,
            durationMin = 45,
            durationStr = "45m",
            batteryDetails = ChargeBatteryDetails(startBatteryLevel = 15, endBatteryLevel = 75),
            rangeIdeal = ChargeRange(startRange = 90.0, endRange = 380.0),
            rangeRated = ChargeRange(startRange = 70.0, endRange = 300.0),
            outsideTempAvg = 22.0,
            odometer = 41000.0,
            latitude = 52.4000,
            longitude = 13.0600
        )
    )

    fun getChargeDetail(chargeId: Int): ChargeDetail = ChargeDetail(
        chargeId = chargeId,
        startDate = isoPast(1440),
        endDate = isoPast(1380),
        address = "Supercharger Berlin",
        chargeEnergyAdded = 45.2,
        chargeEnergyUsed = 48.1,
        cost = 0.0,
        durationMin = 60,
        durationStr = "1h 0m",
        batteryDetails = ChargeBatteryDetails(startBatteryLevel = 20, endBatteryLevel = 80),
        rangeIdeal = ChargeRange(startRange = 120.0, endRange = 410.0),
        rangeRated = ChargeRange(startRange = 95.0, endRange = 320.0),
        outsideTempAvg = 18.5,
        odometer = 42000.0,
        latitude = 52.4500,
        longitude = 13.4000,
        chargePoints = listOf(
            ChargePoint(date = isoPast(1440), batteryLevel = 20, chargeEnergyAdded = 0.0,
                chargerDetails = ChargerDetails(chargerPower = 150, chargerVoltage = 400, chargerActualCurrent = 300, chargerPhases = 0),
                outsideTemp = 18.0),
            ChargePoint(date = isoPast(1410), batteryLevel = 45, chargeEnergyAdded = 22.0,
                chargerDetails = ChargerDetails(chargerPower = 150, chargerVoltage = 400, chargerActualCurrent = 300, chargerPhases = 0),
                outsideTemp = 18.5),
            ChargePoint(date = isoPast(1380), batteryLevel = 80, chargeEnergyAdded = 45.2,
                chargerDetails = ChargerDetails(chargerPower = 80, chargerVoltage = 390, chargerActualCurrent = 205, chargerPhases = 0),
                outsideTemp = 19.0)
        ),
        isCharging = false
    )

    fun getDrives(): List<DriveData> = listOf(
        DriveData(
            driveId = 2001,
            startDate = isoPast(120),
            endDate = isoPast(90),
            startAddress = "Home",
            endAddress = "Office",
            odometerDetails = DriveOdometerDetails(
                odometerStart = 42300.0,
                odometerEnd = 42350.7,
                distance = 50.7
            ),
            durationMin = 30,
            durationStr = "30m",
            speedMax = 135,
            speedAvg = 45.2,
            powerMax = 210,
            powerMin = -15,
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 75, endBatteryLevel = 68),
            rangeIdeal = DriveRange(startRange = 380.0, endRange = 330.0, rangeDiff = -50.0),
            rangeRated = DriveRange(startRange = 300.0, endRange = 270.0, rangeDiff = -30.0),
            outsideTempAvg = 14.5,
            insideTempAvg = 21.0,
            energyConsumedNet = 8.2,
            consumptionNet = 162.0
        ),
        DriveData(
            driveId = 2002,
            startDate = isoPast(1440),
            endDate = isoPast(1410),
            startAddress = "Office",
            endAddress = "Home",
            odometerDetails = DriveOdometerDetails(
                odometerStart = 42250.0,
                odometerEnd = 42300.0,
                distance = 50.0
            ),
            durationMin = 35,
            durationStr = "35m",
            speedMax = 120,
            speedAvg = 42.8,
            powerMax = 195,
            powerMin = -20,
            batteryDetails = DriveBatteryDetails(startBatteryLevel = 70, endBatteryLevel = 63),
            rangeIdeal = DriveRange(startRange = 350.0, endRange = 310.0, rangeDiff = -40.0),
            rangeRated = DriveRange(startRange = 275.0, endRange = 250.0, rangeDiff = -25.0),
            outsideTempAvg = 16.0,
            insideTempAvg = 22.0,
            energyConsumedNet = 7.8,
            consumptionNet = 156.0
        )
    )

    fun getDriveDetail(driveId: Int): DriveDetail = DriveDetail(
        driveId = driveId,
        startDate = isoPast(120),
        endDate = isoPast(90),
        startAddress = "Home",
        endAddress = "Office",
        odometerDetails = DriveOdometerDetails(
            odometerStart = 42300.0,
            odometerEnd = 42350.7,
            distance = 50.7
        ),
        durationMin = 30,
        durationStr = "30m",
        speedMax = 135,
        speedAvg = 45.2,
        powerMax = 210,
        powerMin = -15,
        batteryDetails = DriveBatteryDetails(startBatteryLevel = 75, endBatteryLevel = 68),
        rangeIdeal = DriveRange(startRange = 380.0, endRange = 330.0, rangeDiff = -50.0),
        rangeRated = DriveRange(startRange = 300.0, endRange = 270.0, rangeDiff = -30.0),
        outsideTempAvg = 14.5,
        insideTempAvg = 21.0,
        energyConsumedNet = 8.2,
        consumptionNet = 162.0,
        positions = listOf(
            DrivePosition(date = isoPast(120), latitude = 52.5200, longitude = 13.4050,
                speed = 0, power = 0, batteryLevel = 75, elevation = 85),
            DrivePosition(date = isoPast(110), latitude = 52.5100, longitude = 13.3900,
                speed = 80, power = 120, batteryLevel = 73, elevation = 78),
            DrivePosition(date = isoPast(100), latitude = 52.5000, longitude = 13.3800,
                speed = 120, power = 180, batteryLevel = 71, elevation = 72),
            DrivePosition(date = isoPast(90), latitude = 52.4900, longitude = 13.3700,
                speed = 0, power = -15, batteryLevel = 68, elevation = 70)
        )
    )

    fun getBatteryHealth(): BatteryHealth = BatteryHealth(
        maxRange = 450.0,
        currentRange = 430.0,
        maxCapacity = 75.0,
        currentCapacity = 72.5,
        ratedEfficiency = 153.0,
        batteryHealthPercentage = 96.7
    )

    fun getUpdates(): List<UpdateData> = listOf(
        UpdateData(id = 1, version = "2024.38.7", startDate = isoPast(10080), endDate = isoPast(10020)),
        UpdateData(id = 2, version = "2024.32.3", startDate = isoPast(20160), endDate = isoPast(20100)),
        UpdateData(id = 3, version = "2024.26.1", startDate = isoPast(30240), endDate = isoPast(30180))
    )

    fun getGlobalSettings(): GlobalSettingsData = GlobalSettingsData(
        settings = com.matelink.data.api.models.GlobalSettings(
            teslamateUrls = com.matelink.data.api.models.TeslamateUrls(
                baseUrl = "http://localhost:4000",
                grafanaUrl = "http://localhost:3000"
            ),
            teslamateUnits = com.matelink.data.api.models.TeslamateUnits(
                unitOfLength = "km",
                unitOfTemperature = "°C"
            )
        )
    )

    fun getUnits(): Units = Units(
        unitOfLength = "km",
        unitOfPressure = "bar",
        unitOfTemperature = "°C"
    )
}
