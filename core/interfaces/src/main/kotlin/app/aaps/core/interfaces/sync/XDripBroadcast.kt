package app.aaps.core.interfaces.sync

/**
 * Send data to xDrip+ via Inter-app settings
 */
interface XDripBroadcast {

    fun isEnabled(): Boolean

    /**
     *  Send calibration to xDrip+
     *  Accepting must be enabled in Inter-app settings - Accept Calibrations
     */
    fun sendCalibration(bg: Double): Boolean

    /**
     *  Send data to xDrip+
     *
     *  Accepting must be enabled in Inter-app settings - Accept Glucose/Treatments
     */
    fun sendToXdrip(collection: String, dataPair: DataSyncSelector.DataPair, progress: String)

    /**
     *  Send data to xDrip+
     *
     *  Accepting must be enabled in Inter-app settings - Accept Glucose/Treatments
     */
    fun sendToXdrip(
        collection: String, dataPairs: List<DataSyncSelector.DataPair>, progress:
        String
    )


    /**
     * 渣渣威-1-获取自定义校准数据
     */
    fun getCustomCalibrationDiff():String



    /**
     * 渣渣威-1-校准操作-录入当前血糖数据
     */
    fun sendCustomCalibration(bg: Double):Boolean

    /**
     * 渣渣威-1 返回校准后的血糖数据
     */
    fun getBgWithCustomCalibration(bg:Double):Double

}