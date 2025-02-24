package app.aaps.plugins.sync.xdrip

import android.app.NotificationManager
import android.content.Context
import android.icu.text.SimpleDateFormat
import androidx.core.app.NotificationCompat
import app.aaps.core.data.configuration.Constants
import app.aaps.core.data.model.UE
import app.aaps.core.data.plugin.PluginType
import app.aaps.core.data.ue.Action
import app.aaps.core.data.ue.Sources
import app.aaps.core.data.ue.ValueWithUnit
import app.aaps.core.interfaces.aps.Loop
import app.aaps.core.interfaces.configuration.Config
import app.aaps.core.interfaces.db.PersistenceLayer
import app.aaps.core.interfaces.iob.GlucoseStatusProvider
import app.aaps.core.interfaces.iob.IobCobCalculator
import app.aaps.core.interfaces.logging.AAPSLogger
import app.aaps.core.interfaces.logging.UserEntryLogger
import app.aaps.core.interfaces.notifications.Notification
import app.aaps.core.interfaces.nsclient.ProcessedDeviceStatusData
import app.aaps.core.interfaces.plugin.ActivePlugin
import app.aaps.core.interfaces.plugin.PluginBase
import app.aaps.core.interfaces.plugin.PluginDescription
import app.aaps.core.interfaces.profile.ProfileFunction
import app.aaps.core.interfaces.profile.ProfileUtil
import app.aaps.core.interfaces.resources.ResourceHelper
import app.aaps.core.interfaces.sharedPreferences.SP
import app.aaps.core.interfaces.sync.DataSyncSelector
import app.aaps.core.interfaces.sync.Sync
import app.aaps.core.interfaces.utils.DecimalFormatter
import app.aaps.core.interfaces.utils.TrendCalculator
import app.aaps.core.interfaces.utils.fabric.FabricPrivacy
import app.aaps.core.keys.BooleanKey
import app.aaps.core.keys.DoubleKey
import app.aaps.core.keys.Preferences
import app.aaps.core.ui.toast.ToastUtils
import app.aaps.plugins.sync.R
import io.reactivex.rxjava3.core.Single
import org.apache.commons.lang3.StringUtils
import org.json.JSONException
import org.json.JSONObject
import java.math.BigDecimal
import java.math.RoundingMode
import java.time.LocalDateTime
import java.util.Date
import javax.inject.Inject
import javax.inject.Singleton


@Singleton
class BaseCustomPlugin @Inject constructor(
    private val sp: SP,
    private val context: Context,
    private val aapsLogger: AAPSLogger,
    private val persistenceLayer: PersistenceLayer,
    private var uel: UserEntryLogger,
    private var profileFunction: ProfileFunction,
    private val decimalFormatter: DecimalFormatter

) {

    @Inject lateinit var activePlugin: ActivePlugin
    @Inject lateinit var fabricPrivacy: FabricPrivacy


    // @Inject lateinit var overviewData: OverviewData
    @Inject lateinit var iobCobCalculator: IobCobCalculator
    @Inject lateinit var trendCalculator: TrendCalculator
    @Inject lateinit var glucoseStatusProvider: GlucoseStatusProvider
    @Inject lateinit var processedDeviceStatusData: ProcessedDeviceStatusData
    @Inject lateinit var profileUtil: ProfileUtil
    @Inject lateinit var rh: ResourceHelper
    @Inject lateinit var config: Config
    @Inject lateinit var loop: Loop
    @Inject lateinit var preferences: Preferences




    /**
     * 返回自定义ISF算法状态
     */
    fun customISFStatus():Boolean {
        /**当前功能开启 并且当前设备已被验证通过*/
        // return sp.getBoolean("key_custom_isf",false) && checkCustomFunctionStatus()
        return true
    }
    /**
     * 是否使用校准
     */
    fun customIsUserCalibration():Boolean {
        /**当前功能开启 并且当前设备已被验证通过*/
        return  preferences.get(BooleanKey.OverviewShowCalibrationButton) && checkCustomFunctionStatus()
    }

    /**
     * 录入校准操作
     * 数据发送过来-记录
     */
    fun sendCustomCalibration(calibrationBg: Double): Boolean {
        val logBg = calibrationBg.toBigDecimal().setScale(1, RoundingMode.HALF_UP).toString()
        ToastUtils.showToastInUiThread(context, logBg+"已使用校准,稍后确认血糖更新是否已校准")
        aapsLogger.debug(logBg+"已使用校准,已执行新的校准")
        return true
    }

    /***
     * 返回校准后的血糖数据
     * 如果开启了就校准
     * 没开启就返回元数据
     */
    fun getBgWithCustomCalibration(originBG: Double):Double{
        /**设备是否被验证并允许校准**/
        var  isUserCalibration:Boolean=  customIsUserCalibration()

        aapsLogger.debug("使用校准后的血糖作为aaps血糖来源状态:"+isUserCalibration)
        if(isUserCalibration){
            return customCalibrationGlucose(originBG)

        }
        return originBG
    }

    /**
     * 将血糖数据校准后数据返回使用
     */
    fun customCalibrationGlucose(bg: Double): Double {
        //将误差去1位小数再放入
        var diffBgRecord: Double =sp.getDouble(DoubleKey.CustomCalibrationDiffValue.key, 0.0)
        if(diffBgRecord!=0.0){
            var diffMg:Double = diffBgRecord*Constants.MMOLL_TO_MGDL
            var calibrationBG =bg-diffMg;
            aapsLogger.debug("血糖校准前:$bg 校准后:$calibrationBG")
            return calibrationBG;
        }
        //获取最后一条校准记录
        var note: String =
            persistenceLayer.getUserEntryDataLastCalibrationBg()

        if (note !=null) {
            //最后一次校准值
            if(note.isNotEmpty() && !note.contains("diff")){
                val cgmBgMmol = (bg / Constants.MMOLL_TO_MGDL).toBigDecimal().setScale(1, RoundingMode.HALF_UP).toDouble()

                val units = profileFunction.getUnits()
                var calibrationVal: String = note
                val calibrationValMmol: Double = calibrationVal.toDouble()
                val diff = (cgmBgMmol-calibrationValMmol).toBigDecimal().setScale(1, RoundingMode.HALF_UP).toDouble()
                note= "{'cgm':$cgmBgMmol, 'diff':$diff}"
                //记录一条新的差异
                uel.log(action = Action.CALIBRATION, source=Sources.CalibrationDialog, note, ValueWithUnit.fromGlucoseUnit(calibrationValMmol, units))

            }
            aapsLogger.debug("上次校准记录:"+note)

            val noteObject = JSONObject(note)

            var diff = noteObject.getDouble("diff")
            val cgm = noteObject.getDouble("cgm")

            /**处理自定义校准规则***/
            // try {
            //     var customCalibrationRule:String = sp.getString("custom_calibration_rule","")
            //     if(customCalibrationRule!=null && StringUtils.isNoneBlank(customCalibrationRule)) {
            //         val jsonObject = JSONObject(customCalibrationRule)
            //         var bigPercent = jsonObject.getDouble("bigPercent")
            //         var smallPercent = jsonObject.getDouble("smallPercent")
            //         var finalPercent :Double
            //         finalPercent=0.0
            //         var cgmDiff =  (bg/Constants.MMOLL_TO_MGDL).toBigDecimal().setScale(1, BigDecimal.ROUND_HALF_UP).toDouble()-cgm
            //
            //         if(cgmDiff>1){
            //             finalPercent=cgmDiff*bigPercent
            //         }else if(cgmDiff<-1){
            //             finalPercent=cgmDiff*smallPercent
            //         }
            //         diff =(1+finalPercent) *diff
            //     }
            // }catch (e:Exception){
            //     aapsLogger.debug("处理自定义校准规则异常"+e.message)
            // }
            /**处理自定义校准规则***/

            //将误差去1位小数再放入
            sp.putDouble(DoubleKey.CustomCalibrationDiffValue.key,diff)

            var diffMg:Double = diff*Constants.MMOLL_TO_MGDL
            var calibrationBG =bg-diffMg;
            aapsLogger.debug("血糖校准前:$bg 校准后:$calibrationBG")
            return calibrationBG;
        }else{
            return bg
        }
    }



    /**
     * 计算预测血糖用于计算isf
     */
    fun getCustomPredBg(): Double {
        val glucoseStatus = glucoseStatusProvider.glucoseStatusData
        if(glucoseStatus==null){
            return 0.0;
        }
        /**如果开关未开启 或者设备未被验证 则使用默认的isf规则*/
        if (!customISFStatus()) {
            return  glucoseStatus.glucose
        }
        var currentBg = glucoseStatus.glucose
        var bg5Diff = glucoseStatus.delta
        var bg15Diff =glucoseStatus.shortAvgDelta
        var bg40Diff = glucoseStatus.longAvgDelta

        /**如果配置了自定义预测血糖规则 按照规则进行计算*/
        try {
            var predBgConfig =sp.getString("key_custom_predBG_config","")
            if(predBgConfig!=null && StringUtils.isNoneBlank(predBgConfig)){
                val jsonObject = JSONObject(predBgConfig)
                var multiple5diff = jsonObject.getDouble("multiple5diff")
                var multiple15diff = jsonObject.getDouble("multiple15diff")
                var multiple40diff = jsonObject.getDouble("multiple40diff")
                if(multiple5diff!=null){
                    bg5Diff*=multiple5diff
                }
                if(multiple15diff!=null){
                    bg15Diff*=multiple15diff
                }
                if(multiple40diff!=null){
                    bg40Diff*=multiple40diff
                }
            }
        }catch (e:Exception){
            aapsLogger.debug("处理自定义预测血糖规则异常"+e)
        }

        /**预测血糖= 5分钟变化率 + 15分钟变化率+ 40分钟变化率 相当于预测15分钟后的血糖 用于提前给药**/
        var predBg =currentBg+ bg5Diff + bg15Diff + bg40Diff
        return  predBg
    }

    /**
     * 自定义TDD限制
     * 返回限制后的tdd
     */
    fun customTddLimit(tdd: Double): Double {

        /**如果开关未开启则使用默认的tdd*/
        if (!customISFStatus()) {
            return  tdd
        }
        var tddConfigJson =sp.getString("key_custom_tdd_limit","")
        if(tddConfigJson!=null && StringUtils.isNoneBlank(tddConfigJson)){
            val jsonObject = JSONObject(tddConfigJson)
            var tddMin = jsonObject.getDouble("tddMin")
            var tddMax = jsonObject.getDouble("tddMax")
            if(tdd>tddMax){
                return tddMax
            }
            if(tdd<tddMin){
                return tddMin
            }
            return tdd
        }
        return tdd
    }





    /**
     * 获取自定义校准的差异数值
     * 用于首页显示
     */
    fun getCustomCalibrationDiff():String {
        var  isUserCalibration:Boolean= customIsUserCalibration()

        if(!isUserCalibration){
            return "✈"
        }
        var diff:Double = sp.getDouble(DoubleKey.CustomCalibrationDiffValue.key,0.0).toBigDecimal().setScale(1, RoundingMode.HALF_UP).toDouble()
        var showDiff=(0-diff);
        if(showDiff>0){
            return "+"+ showDiff.toString();
        }
        return showDiff.toString()

    }

    /**
     * 发送消息通知
     */
    // fun sendGlucoseNotice(glucoseValues: MutableList<TransactionResult>, source: String) {
    //
    //     try {
    //
    //
    //         var  sendCustomNotice:Boolean=  activePlugin.activeBgSource.useSendCustomNotice()
    //         if (!sendCustomNotice){
    //             aapsLogger.debug("未开启消息通知-不进行通知")
    //             return
    //         }
    //         //初始化通知用到的相关限制参数
    //         var bgMin=0
    //         var bgMax=0
    //         var bgMin5Diff=0
    //         var bgMax5Diff=0
    //         var bg5Diff=0.0
    //         var timeoutAfter=10 //消息多少s以后关闭
    //         var configJson =sp.getString("key_bg_notice_config","")
    //         if(configJson!=null && StringUtils.isNoneBlank(configJson)){
    //             val jsonObject = JSONObject(configJson)
    //             bgMin = jsonObject.getInt("bgMin")
    //             bgMax = jsonObject.getInt("bgMax")
    //             bgMin5Diff = jsonObject.getInt("bgMin5Diff")
    //             bgMax5Diff = jsonObject.getInt("bgMax5Diff")
    //             timeoutAfter = jsonObject.getInt("timeoutAfter")
    //         }
    //         timeoutAfter*=1000
    //
    //         val glucoseStatus = glucoseStatusProvider.glucoseStatusData
    //
    //
    //
    //         aapsLogger.debug("begin send message")
    //         val builder = NotificationCompat.Builder(context, "AAPS-OpenLoop")
    //         var count:Int =0
    //         glucoseValues.forEach {
    //             if(glucoseStatus!=null){
    //                 bg5Diff =it.value - glucoseStatus.glucose
    //             }
    //
    //             /**血糖再上下限之间*/
    //             if(it.value<bgMax && it.value>bgMin){
    //                 /**并且血糖波动再波动限制范围内不通知*/
    //                 if(bg5Diff<bgMax5Diff && bg5Diff>bgMin5Diff){
    //                     return@forEach
    //                 }
    //             }
    //             /**只在第一次通知*/
    //             if(count==0){
    //                 var diffValue= BigDecimal.valueOf(bg5Diff/Constants.MMOLL_TO_MGDL).setScale(1,BigDecimal.ROUND_HALF_UP).toDouble().toString()
    //                 if(bg5Diff>=0){
    //                     diffValue="+"+diffValue
    //                 }else{
    //                     diffValue=diffValue
    //                 }
    //                 var sgv = it.value / Constants.MMOLL_TO_MGDL
    //                 val sdf = SimpleDateFormat("HH:mm")
    //                 val date = sdf.format(Date(it.timestamp)).toString()
    //
    //                 val contentText = sgv.toBigDecimal().setScale(1, BigDecimal.ROUND_HALF_UP).toString() +
    //                     it.trendArrow.symbol  + diffValue
    //                 val title =source+"\r " + date + "\r "
    //                 builder.setSmallIcon(app.aaps.core.ui.R.drawable.notif_icon)
    //                     .setContentTitle(title)
    //                     .setContentText(contentText)
    //                     .setAutoCancel(true)
    //                     .setTimeoutAfter(timeoutAfter.toLong()) //设置多少s后自动关闭
    //                     .setPriority(Notification.IMPORTANCE_HIGH)
    //                     .setCategory(Notification.CATEGORY_ALARM)
    //                     .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
    //                     .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))
    //                 val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
    //                 mNotificationManager.notify(Constants.notificationID, builder.build())
    //                 count++
    //                 return@forEach
    //             }
    //
    //         }
    //     }catch (e: JSONException){
    //         aapsLogger.debug("老渣发送穿戴设备通知异常")
    //
    //     }
    // }

    // fun sendBolusNotice(bolus: Double) {
    //     var  sendCustomNotice:Boolean=  activePlugin.activeBgSource.useSendCustomNotice()
    //     if (!sendCustomNotice){
    //         aapsLogger.debug("未开启消息通知-不进行通知")
    //         return
    //     }
    //     sendBolusNotice(bolus,"大剂量输注完毕")
    // }

    /**指定title的大剂量通知 不需要验证开关，主要用于NS远程输注*/
    fun sendBolusNotice(bolus: Double,title:String) {
        try {
            val sdf = SimpleDateFormat("HH:mm")
            val date = sdf.format(Date()).toString()
            val builder = NotificationCompat.Builder(context, "AAPS-OpenLoop")
            builder.setSmallIcon(app.aaps.core.ui.R.drawable.notif_icon)
                .setContentTitle(title)
                .setContentText(bolus.toString()+" U  "+date)
                .setAutoCancel(true)
                .setPriority(Notification.IMPORTANCE_HIGH)
                .setCategory(Notification.CATEGORY_ALARM)
                .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
                .setVibrate(longArrayOf(1000, 1000, 1000, 1000, 1000))
            val mNotificationManager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            mNotificationManager.notify(Constants.notificationID, builder.build())
        }catch (e: JSONException){
            aapsLogger.debug("老渣发送大剂量通知异常")
        }
    }

    /**
     * 自定义始终smb限制规则
     */
    fun customAlwaysSmbStatus(alwaysSMB:Boolean):Boolean {
        if(!alwaysSMB){
            return alwaysSMB
        }
        /**如果当前通道没有允许使用始终SMB*/
        if(!activePlugin.activeBgSource.advancedFilteringSupported()){
            return  activePlugin.activeBgSource.advancedFilteringSupported()
        }
        /**如果没有特殊设置也返回始终smb*/
        var customAlwaysSMBConfigJson =sp.getString("key_custom_always_smb_config","")
        if(customAlwaysSMBConfigJson==null || StringUtils.isBlank(customAlwaysSMBConfigJson)){
            return true
        }
        val currentDateTime = LocalDateTime.now()
        val currentHour = currentDateTime.hour
        var limitHostList : List<String> = customAlwaysSMBConfigJson.split(";")
        if(limitHostList.contains(currentHour.toString())){
            return false
        }
        return true
    }


    fun sendCalibration(bg: Double): Boolean {
        return false
    }

    fun sendToXdrip(collection: String, dataPair: DataSyncSelector.DataPair, progress: String) {
    }

    fun sendToXdrip(collection: String, dataPairs: List<DataSyncSelector.DataPair>, progress: String) {
    }

    /**
     * 检查uuid 当前用户设备是否允许被使用
     * TODO 测试时true 对外改成false
     */
    fun checkCustomFunctionStatus(): Boolean {
        var customFunctionStatus:Boolean =sp.getBoolean("key_custom_uuid_status",true)
        return customFunctionStatus

    }


}




