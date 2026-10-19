package io.github.chargercontroller

import android.app.Activity
import android.graphics.Color
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.widget.*
import io.github.chargercontroller.core.ControlMode

/** Native presentation layer. All account, network and protection actions remain in MainActivity. */
internal class FluentScreen(private val activity: Activity, low: Int, high: Int, mode: ControlMode,
    private val action: (String) -> Unit) {
    val root = FrameLayout(activity).apply { setBackgroundColor(Fluent.canvas) }
    private val scaffold = column()
    private val toastHandler=android.os.Handler(android.os.Looper.getMainLooper())
    private val toastView=label("",12,Color.WHITE).apply{tag="app-toast";gravity=Gravity.CENTER;setPadding(dp(14),dp(12),dp(14),dp(12));background=Fluent.surface(activity,Color.rgb(41,41,41),8,null);visibility=View.GONE;importantForAccessibility=View.IMPORTANT_FOR_ACCESSIBILITY_YES}
    private val hideToast=Runnable{toastView.visibility=View.GONE}
    private val content = FrameLayout(activity)
    private val pages = linkedMapOf<String, ScrollView>()
    private val navigation = LinearLayout(activity).apply { setBackgroundColor(Color.rgb(250,250,250)); gravity=Gravity.CENTER }
    private val tabs = mutableMapOf<String, LinearLayout>()
    private val protectedButtons = mutableListOf<View>()
    private val busyButtons = mutableListOf<View>()
    private val modeButtons = mutableMapOf<ControlMode, Button>()
    val result = label("状态：未查询",13)
    val batteryStatus = label("正在读取手机电量",13)
    val protectionStatus = label("",13)
    val routeStatus = label("",12)
    val accountStatus = label("尚未登录",12)
    val deviceStatus = label("尚未选择插座",12)
    val operationStatus = label("尚无操作记录",12)
    val logView = label("尚无操作记录",12)
    val highInput = numeric(high,"高电量关闭阈值","threshold-high")
    val lowInput = numeric(low,"低电量开启阈值","threshold-low")
    val range = ThresholdRangeView(activity).apply { values(low,high);tag="threshold-range" }
    var selectedMode = mode; private set
    var selectedPage = "home"; private set
    var dirty = false; private set
    private var syncing = false
    private var active = false
    private var busy = false
    private var filter = "全部"
    private var entries: List<String> = emptyList()
    private val gauge = BatteryGauge(activity)
    private val homeDevice = label("选择目标插座",12)
    private val badge = label("保护未启动",11,Fluent.muted)
    private val summaryTitle = label("尚未开始充电保护",14,Fluent.ink,true)
    private val summaryDetail = label("",12).apply{maxLines=2;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val guardButton = button("开始保护","toggle-guard",true)
    private val stopEdit = button("停止保护","stop",false)
    private val runningHint = LinearLayout(activity)
    private val saveButton = button("保存保护设置","save",true)
    private val lowSummary = label("$low%",18,Fluent.ink,true)
    private val highSummary = label("$high%",18,Fluent.ink,true)
    private val plugTitle = label("插座状态未查询",14,Fluent.ink,true)
    private val plugMeta = label("点击刷新以确认状态",11).apply{maxLines=1;ellipsize=android.text.TextUtils.TruncateAt.END}
    private val permissionSide = label("建议设置",10)
    private val routeCaption = label("",12)
    private val validation = label("",12,Fluent.red)
    private val logList = column()
    private val profileTitle = label("连接小米账号",14,Fluent.ink,true)
    private val profileDetail = label("登录后可使用云端控制",11)
    private val routeLink = label("自动模式 ›",11)
    private val lockNote = label("手动控制前，请先停止保护",10)
    private val latestTime = label("查看运行记录",10)
    private val logFilters = mutableMapOf<String, Button>()

    init {
        root.addView(scaffold,FrameLayout.LayoutParams(-1,-1))
        scaffold.addView(content,LinearLayout.LayoutParams(-1,0,1f))
        scaffold.addView(View(activity).apply{setBackgroundColor(Fluent.line)},LinearLayout.LayoutParams(-1,dp(1)))
        scaffold.addView(navigation,LinearLayout.LayoutParams(-1,dp(68)))
        root.addView(toastView,FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM).apply{leftMargin=dp(24);rightMargin=dp(24);bottomMargin=dp(94)})
        buildHome();buildProtection();buildSettings();buildLogs()
        for((key,title,icon) in listOf(Triple("home","首页","home"),Triple("protection","保护","shield"),Triple("settings","设置","settings"))) {
            val tab=column().apply { gravity=Gravity.CENTER;tag="nav-$key";isClickable=true;isFocusable=true;contentDescription=title }
            tab.addView(View(activity).apply { tag="nav-line-$key" },LinearLayout.LayoutParams(dp(24),dp(3)).apply{bottomMargin=dp(5)})
            tab.addView(FluentIcon(activity,icon),LinearLayout.LayoutParams(dp(22),dp(22)))
            tab.addView(label(title,11),LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(3)})
            navigation.addView(tab,LinearLayout.LayoutParams(0,-1,1f));tabs[key]=tab
            tab.setOnClickListener{show(key)}
        }
        val watcher=object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(syncing)return
                dirty=true
                range.values(lowInput.text.toString().toIntOrNull()?:1,highInput.text.toString().toIntOrNull()?:100)
                validate(false)
            }
            override fun afterTextChanged(e:Editable?)=Unit
        }
        lowInput.addTextChangedListener(watcher);highInput.addTextChangedListener(watcher)
        range.onValuesChanged={l,h->syncing=true;lowInput.setText(l.toString());highInput.setText(h.toString());syncing=false;dirty=true;validate(false)}
        refreshMode();updateSavedMode(mode);show("home")
    }
    private fun dp(n:Int)=Fluent.dp(activity,n)
    private fun column()=LinearLayout(activity).apply{orientation=LinearLayout.VERTICAL}
    private fun label(value:String,size:Int,color:Int=Fluent.muted,bold:Boolean=false)=TextView(activity).apply {
        text=value;textSize=size.toFloat();setTextColor(color);setLineSpacing(dp(2).toFloat(),1f)
        if(bold)typeface=Typeface.DEFAULT_BOLD
    }
    private fun icon(name:String,color:Int=Fluent.muted)=FluentIcon(activity,name,color)
    private fun button(title:String,key:String,primary:Boolean=false)=Button(activity).apply {
        text=title;tag=key;isAllCaps=false;textSize=13f;minHeight=dp(44);minimumHeight=dp(44);minWidth=0;minimumWidth=0
        setPadding(dp(12),0,dp(12),0);setTextColor(if(primary)Color.WHITE else Fluent.ink)
        background=Fluent.surface(activity,if(primary)Fluent.blue else Color.WHITE,6,if(primary)null else Color.rgb(209,209,209))
        stateListAnimator=null
        setOnClickListener{action(key)}
    }
    private fun numeric(value:Int,name:String,key:String)=EditText(activity).apply {
        tag=key;contentDescription=name;setText(value.toString());inputType=InputType.TYPE_CLASS_NUMBER;isSingleLine=true
        textSize=34f;typeface=Typeface.create("sans-serif-light",Typeface.NORMAL);setTextColor(Fluent.ink)
        setPadding(dp(4),0,dp(4),0);setSelectAllOnFocus(true);isSaveEnabled=false
        backgroundTintList=android.content.res.ColorStateList(arrayOf(intArrayOf(android.R.attr.state_focused),intArrayOf()),intArrayOf(Fluent.blue,Fluent.muted))
    }
    private fun page(key:String,title:String,subtitle:String?=null):LinearLayout {
        val body=(if(key=="logs")LogContentLayout(activity) else column()).apply{setPadding(dp(22),dp(22),dp(22),dp(24))}
        val scroll=ScrollView(activity).apply{isFillViewport=true;isVerticalScrollBarEnabled=false;addView(body,FrameLayout.LayoutParams(-1,-2,Gravity.CENTER_HORIZONTAL));tag="page-$key"}
        pages[key]=scroll;content.addView(scroll,FrameLayout.LayoutParams(-1,-1))
        val header=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        val heading=column();heading.addView(label(title,26,Fluent.ink,true))
        if(subtitle!=null)heading.addView(label(subtitle,12),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})
        header.addView(heading,LinearLayout.LayoutParams(0,-2,1f));body.addView(header)
        return body
    }
    private fun card(parent:LinearLayout):LinearLayout=column().apply {
        setPadding(dp(18),dp(16),dp(18),dp(16));background=Fluent.surface(activity);elevation=dp(1).toFloat()
        parent.addView(this,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
    }
    private fun caption(parent:LinearLayout,title:String,name:String) {
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        row.addView(icon(name),LinearLayout.LayoutParams(dp(16),dp(16)))
        row.addView(label(title,11),LinearLayout.LayoutParams(-2,-2).apply{leftMargin=dp(7)})
        parent.addView(row,LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(12)})
    }
    private fun addButton(parent:LinearLayout,b:Button,locked:Boolean=true) {
        parent.addView(b,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(12)})
        if(locked)protectedButtons+=b
        busyButtons+=b
    }
    private fun buildHome() {
        val body=page("home","我的充电")
        homeDevice.setOnClickListener{action("devices")};homeDevice.isFocusable=true
        val header=body.getChildAt(0) as LinearLayout
        (header.getChildAt(0) as LinearLayout).addView(homeDevice,LinearLayout.LayoutParams(-2,dp(27)))
        val alerts=FrameLayout(activity).apply{tag="alerts";isClickable=true;isFocusable=true;contentDescription="查看异常记录";background=Fluent.surface(activity,Color.WHITE,8);setOnClickListener{showLogs("异常")}}
        alerts.addView(icon("bell"),FrameLayout.LayoutParams(dp(19),dp(19),Gravity.CENTER))
        header.addView(alerts,LinearLayout.LayoutParams(dp(40),dp(40)).apply{leftMargin=dp(12)})
        val hero=card(body)
        val top=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        top.addView(label("手机电量",12),LinearLayout.LayoutParams(0,-2,1f))
        badge.background=Fluent.surface(activity,Fluent.soft,4,null);badge.setPadding(dp(7),dp(4),dp(7),dp(4));top.addView(badge)
        hero.addView(top)
        hero.addView(gauge,LinearLayout.LayoutParams(dp(190),dp(190)).apply{gravity=Gravity.CENTER_HORIZONTAL;topMargin=dp(8)})
        val limits=LinearLayout(activity)
        for((name,value,arrow) in listOf(Triple("低电量开启",lowSummary,"down"),Triple("高电量关闭",highSummary,"up"))) {
            val item=LinearLayout(activity).apply{gravity=Gravity.CENTER;setPadding(0,dp(10),0,dp(12))}
            item.addView(icon(arrow),LinearLayout.LayoutParams(dp(18),dp(18)).apply{rightMargin=dp(8)})
            val copy=column();copy.addView(label(name,10));copy.addView(value);item.addView(copy)
            limits.addView(item,LinearLayout.LayoutParams(0,-2,1f))
        }
        hero.addView(limits)
        hero.addView(View(activity).apply{setBackgroundColor(Fluent.line)},LinearLayout.LayoutParams(-1,dp(1)))
        hero.addView(summaryTitle,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(14)})
        hero.addView(summaryDetail,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})
        hero.addView(guardButton,LinearLayout.LayoutParams(-1,dp(44)).apply{topMargin=dp(14)})
        val section=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        section.addView(label("插座控制",14,Fluent.ink,true),LinearLayout.LayoutParams(0,-2,1f))
        routeLink.setPadding(dp(4),dp(12),0,dp(12));routeLink.setOnClickListener{show("protection")};routeLink.isFocusable=true
        section.addView(routeLink)
        body.addView(section,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(18)})
        val plug=card(body)
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        row.addView(icon("plug"),LinearLayout.LayoutParams(dp(25),dp(25)).apply{rightMargin=dp(10)})
        val copy=column();copy.addView(plugTitle);copy.addView(plugMeta,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        for((title,key) in listOf("开启" to "on","关闭" to "off")) {
            val b=button(title,key);b.textSize=12f;b.setPadding(0,0,0,0)
            row.addView(b,LinearLayout.LayoutParams(dp(48),dp(44)).apply{leftMargin=dp(6)})
            protectedButtons+=b;busyButtons+=b
        }
        plug.addView(row)
        lockNote.gravity=Gravity.CENTER;body.addView(lockNote,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val latest=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(17),0,dp(8));isClickable=true;isFocusable=true;contentDescription="查看运行记录";setOnClickListener{show("logs")}}
        latest.addView(icon("history"),LinearLayout.LayoutParams(dp(18),dp(18)).apply{rightMargin=dp(8)})
        operationStatus.maxLines=1;operationStatus.ellipsize=android.text.TextUtils.TruncateAt.END;val latestCopy=column();latestCopy.addView(operationStatus);latestCopy.addView(latestTime,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(3)});latest.addView(latestCopy,LinearLayout.LayoutParams(0,-2,1f));latest.addView(icon("chevron"),LinearLayout.LayoutParams(dp(14),dp(14)));body.addView(latest)
    }
    private fun buildProtection() {
        val body=page("protection","保护设置")
        runningHint.apply{gravity=Gravity.CENTER_VERTICAL;setPadding(dp(10),dp(8),dp(10),dp(8));background=Fluent.surface(activity,Fluent.soft,6,null)}
        runningHint.addView(label("保护运行中，停止后可修改设置。",11),LinearLayout.LayoutParams(0,-2,1f))
        stopEdit.textSize=11f;runningHint.addView(stopEdit,LinearLayout.LayoutParams(dp(80),dp(44)))
        body.addView(runningHint,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        val threshold=card(body);caption(threshold,"电量阈值","sliders")
        val names=LinearLayout(activity);names.addView(label("低电量开启",12),LinearLayout.LayoutParams(0,-2,1f));names.addView(label("高电量关闭",12));threshold.addView(names)
        threshold.addView(range,LinearLayout.LayoutParams(-1,dp(48)))
        val scale=LinearLayout(activity)
        scale.addView(label("1%",10));scale.addView(label("拖动两端调整范围",10).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(0,-2,1f));scale.addView(label("100%",10));threshold.addView(scale)
        val fields=LinearLayout(activity)
        for((title,input) in listOf("低电量开启" to lowInput,"高电量关闭" to highInput)) {
            val box=column().apply{setPadding(dp(12),dp(12),dp(12),dp(12));background=Fluent.surface(activity,Color.rgb(250,250,250),8)}
            box.addView(label(title,12));val number=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
            number.addView(input,LinearLayout.LayoutParams(0,dp(56),1f));number.addView(label("%",14));box.addView(number,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
            fields.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply{if(input===highInput)leftMargin=dp(12)})
        }
        threshold.addView(fields,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(16)})
        validation.visibility=View.GONE;threshold.addView(validation,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
        val route=card(body);caption(route,"控制路线","wifi")
        val routes=LinearLayout(activity).apply{setPadding(dp(4),dp(4),dp(4),dp(4));background=Fluent.surface(activity,Color.rgb(240,240,240),6,null)}
        for((m,title) in listOf(ControlMode.AUTO to "自动",ControlMode.LAN to "局域网",ControlMode.CLOUD to "云端")) {
            val b=button(title,"mode-${m.name}");b.setOnClickListener{if(!active&&!busy){selectedMode=m;dirty=true;refreshMode()}}
            routes.addView(b,LinearLayout.LayoutParams(0,dp(44),1f));modeButtons[m]=b
        }
        route.addView(routes);route.addView(routeCaption,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
        addButton(body,saveButton)
        body.addView(label("保存设置不会自动启动保护",11).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(8)})
    }
    private fun menu(parent:LinearLayout,title:String,desc:String,name:String,key:String,locked:Boolean=false,side:TextView?=null) {
        val row=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(12),0,dp(12));minimumHeight=dp(66);tag=key;isClickable=true;isFocusable=true;contentDescription=title;setOnClickListener{action(key)}}
        val symbol=FrameLayout(activity).apply{background=Fluent.surface(activity,Color.rgb(240,240,240),6,null)}
        symbol.addView(icon(name),FrameLayout.LayoutParams(dp(17),dp(17),Gravity.CENTER))
        row.addView(symbol,LinearLayout.LayoutParams(dp(33),dp(33)).apply{rightMargin=dp(11)})
        val copy=column();copy.addView(label(title,12,Fluent.ink));if(desc.isNotEmpty())copy.addView(label(desc,10),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f));side?.let{row.addView(it)};row.addView(icon("chevron"),LinearLayout.LayoutParams(dp(16),dp(16)).apply{leftMargin=dp(8)})
        if(parent.childCount>0)parent.addView(View(activity).apply{setBackgroundColor(Fluent.line)},LinearLayout.LayoutParams(-1,dp(1)))
        parent.addView(row);if(locked)protectedButtons+=row
    }
    private fun buildSettings() {
        val body=page("settings","设置","把连接与管理，安放在这里。")
        val account=card(body)
        val profile=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL;tag="account";isClickable=true;isFocusable=true;contentDescription="小米账号";setOnClickListener{action("account")}}
        val avatar=FrameLayout(activity).apply{background=Fluent.surface(activity,Fluent.soft,8,null)}
        avatar.addView(icon("user",Fluent.blue),FrameLayout.LayoutParams(dp(23),dp(23),Gravity.CENTER))
        profile.addView(avatar,LinearLayout.LayoutParams(dp(43),dp(43)).apply{rightMargin=dp(12)})
        val copy=column();copy.addView(profileTitle);copy.addView(profileDetail,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(5)})
        profile.addView(copy,LinearLayout.LayoutParams(0,-2,1f));profile.addView(icon("chevron"),LinearLayout.LayoutParams(dp(15),dp(15)));account.addView(profile)
        body.addView(label("设备与连接",11),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(20)})
        val devices=card(body).apply{setPadding(dp(14),0,dp(14),0)};menu(devices,"目标插座","选择或编辑插座","plug","devices");menu(devices,"通知与后台","让锁屏后的保护继续运行","bell","permissions",side=permissionSide)
        body.addView(label("记录与维护",11),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(20)})
        val maintenance=card(body).apply{setPadding(dp(14),0,dp(14),0)};menu(maintenance,"运行记录","查看控制结果、重试与异常","history","logs");menu(maintenance,"高级配置","IP、设备凭据与连接测试","sliders","advanced")
        body.addView(label("关于",11),LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(20)})
        val about=card(body).apply{setPadding(dp(14),0,dp(14),0)};menu(about,"关于充电守护","","info","about",side=label("v${activity.packageManager.getPackageInfo(activity.packageName,0).versionName}",10))
    }
    private fun buildLogs() {
        val body=page("logs","运行记录")
        body.removeViewAt(0)
        val head=LinearLayout(activity).apply{gravity=Gravity.CENTER_VERTICAL}
        val back=FrameLayout(activity).apply{tag="back-settings";isClickable=true;isFocusable=true;contentDescription="返回设置";background=Fluent.surface(activity,Color.WHITE,20);setOnClickListener{show("settings")}}
        back.addView(icon("back"),FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
        head.addView(back,LinearLayout.LayoutParams(dp(44),dp(44)))
        head.addView(label("运行记录",24,Fluent.ink,true),LinearLayout.LayoutParams(0,-2,1f).apply{leftMargin=dp(11)})
        val clear=button("清空","clear-logs").apply{background=null;textSize=14f;setTextColor(Fluent.muted);setPadding(0,0,0,0)}
        head.addView(clear,LinearLayout.LayoutParams(dp(64),dp(48)));body.addView(head)
        val filters=LogFiltersLayout(activity).apply{tag="log-filters"}
        for(name in listOf("全部","保护","开关","异常")) {
            val b=button(name,"filter-$name");b.textSize=14f;b.minWidth=dp(64);b.minimumWidth=dp(64);b.setPadding(dp(14),dp(8),dp(14),dp(8));b.setOnClickListener{filter=name;refreshLogs()}
            filters.addView(b,android.view.ViewGroup.LayoutParams(-2,-2));logFilters[name]=b
        }
        body.addView(filters,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(20)})
        body.addView(logList,LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(22)})
    }
    fun showLogs(filter:String="全部") {this.filter=filter;show("logs")}
    fun updateAccount(connected:Boolean,region:String,expired:Boolean) {
        profileTitle.text=if(connected)"小米账号已连接" else "连接小米账号"
        profileDetail.text=if(!connected)"登录后可使用云端控制" else "${if(region=="cn")"中国大陆" else region} · ${if(expired)"会话已失效，请重新登录" else "云端会话已保存"}"
    }
    fun toast(message:String){toastHandler.removeCallbacks(hideToast);toastView.text=message;toastView.visibility=View.VISIBLE;toastView.announceForAccessibility(message);toastHandler.postDelayed(hideToast,2300)}
    fun dispose(){toastHandler.removeCallbacksAndMessages(null)}
    fun show(key:String) {
        (activity.getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(root.windowToken,0)
        selectedPage=key;pages.forEach{(name,view)->view.visibility=if(name==key)View.VISIBLE else View.GONE}
        navigation.visibility=if(key=="logs")View.GONE else View.VISIBLE
        tabs.forEach{(name,tab)->
            tab.isSelected=name==key;tab.findViewWithTag<View>("nav-line-$name").setBackgroundColor(if(name==key)Fluent.blue else Color.TRANSPARENT)
            (tab.getChildAt(1) as FluentIcon).apply{tint=if(name==key)Fluent.blue else Fluent.muted;invalidate()}
            (tab.getChildAt(2) as TextView).setTextColor(if(name==key)Fluent.blue else Fluent.muted)
        }
        if(key=="logs")refreshLogs()
    }
    fun back():Boolean { return if(selectedPage!="home"){show(if(selectedPage=="logs")"settings" else "home");true}else false }
    fun validate(showError:Boolean=true):Boolean {
        val l=lowInput.text.toString().toIntOrNull();val h=highInput.text.toString().toIntOrNull()
        val valid=l!=null&&h!=null&&h in 2..100&&l in 1 until h
        lowInput.error=null;highInput.error=null
        validation.visibility=if(!valid)View.VISIBLE else View.GONE;validation.text="请输入整数：1 ≤ 开启阈值 < 关闭阈值 ≤ 100"
        if(!valid&&showError)show("protection")
        return valid
    }
    fun saved(low:Int,high:Int) { dirty=false;lowSummary.text="$low%";highSummary.text="$high%";updateSavedMode(selectedMode) }
    fun updateSavedMode(mode:ControlMode){routeLink.text=when(mode){ControlMode.AUTO->"自动模式 ›";ControlMode.LAN->"局域网 ›";ControlMode.CLOUD->"云端 ›"}}
    fun restoreDraft(low:String,high:String,mode:ControlMode,dirty:Boolean) {
        syncing=true;lowInput.setText(low.toString());highInput.setText(high.toString());range.values(low.toIntOrNull()?:1,high.toIntOrNull()?:100);selectedMode=mode;this.dirty=dirty;syncing=false;refreshMode();validate(false)
    }
    private fun refreshMode() {
        modeButtons.forEach{(mode,b)->b.isSelected=mode==selectedMode;b.setTextColor(if(b.isSelected)Fluent.blue else Fluent.muted);b.background=Fluent.surface(activity,if(b.isSelected)Color.WHITE else Color.TRANSPARENT,4,null)}
        routeCaption.text=when(selectedMode){ControlMode.AUTO->"优先通过局域网控制，连接失败时使用小米云。";ControlMode.LAN->"通过局域网直连，需要手机与插座网络互通。";ControlMode.CLOUD->"通过小米云控制，手机使用移动数据时也可连接。"}
    }
    fun updateBattery(percent:Int?,plugged:Boolean) { gauge.percent=percent;gauge.plugged=plugged }
    fun updateIdentity(name:String?,notifications:Boolean,background:Boolean) { homeDevice.text=name?:"选择目标插座";((pages["settings"]?.findViewWithTag<LinearLayout>("devices")?.getChildAt(1) as? LinearLayout)?.getChildAt(1) as? TextView)?.text=name?:"选择或编辑插座";permissionSide.text=if(notifications&&background)"已设置" else "建议设置" }
    fun updateState(active:Boolean,busy:Boolean,phase:String,message:String,low:Int,high:Int) {
        this.active=active;this.busy=busy
        protectedButtons.forEach{it.isEnabled=!active&&!busy;it.alpha=if(it.isEnabled)1f else .45f}
        busyButtons.filterNot{it in protectedButtons}.forEach{it.isEnabled=!busy}
        lowInput.isEnabled=!active&&!busy;highInput.isEnabled=!active&&!busy;range.isEnabled=!active&&!busy
        modeButtons.values.forEach{it.isEnabled=!active&&!busy;it.alpha=if(it.isEnabled)1f else .45f}
        runningHint.visibility=if(active)View.VISIBLE else View.GONE
        badge.text=if(active)"保护运行中" else "保护未启动";badge.setTextColor(if(active)Fluent.blue else Fluent.muted)
        guardButton.text=if(active)"停止保护" else "开始保护";guardButton.isEnabled=active||!busy
        guardButton.setTextColor(if(active)Fluent.ink else Color.WHITE);guardButton.background=Fluent.surface(activity,if(active)Color.WHITE else Fluent.blue,6,if(active)Color.rgb(209,209,209) else null)
        summaryTitle.text=if(!active) {if(phase=="FAILED")"保护需要检查" else "尚未开始充电保护"} else when(phase){"WAITING_LOW"->"等待电量降至开启阈值";"MONITORING"->"监测电量，达到阈值自动断电";"RETRY_ON","RETRY_OFF","WAIT_RETRY_ON","WAIT_RETRY_OFF"->"结果未确认，等待重试";else->"正在连接并确认插座状态"}
        summaryDetail.text=if(active)message.substringBefore('\n') else if(phase in listOf("FAILED","INTERRUPTED"))message.substringBefore('\n') else "电量 ≤ $low% 开启，≥ $high% 关闭"
        lowSummary.text="$low%";highSummary.text="$high%"
        lockNote.visibility=if(active)View.VISIBLE else View.GONE
        if(!active&&phase in listOf("FAILED","INTERRUPTED"))showPlug(null,message.substringBefore('\n'))
        if(active)when(phase){"WAITING_LOW"->showPlug(false,"保护任务已读回确认");"MONITORING"->showPlug(true,"保护任务已读回确认");else->showPlug(null,"${message.substringBefore('\n')}")}
    }
    fun showPlug(power:Boolean?,detail:String) {plugTitle.text=when(power){true->"插座已开启";false->"插座已关闭";null->"插座状态未确认"};plugMeta.text=if(power==null&&detail.length>30)"上次动作未确认，请查看记录" else detail}
    fun updateLogs(logs:List<String>) { entries=logs;latestTime.text=logs.firstOrNull()?.take(8)?.let{"$it · 查看运行记录"}?:"查看运行记录";logView.text=logs.joinToString("\n").ifEmpty{"尚无操作记录"};if(selectedPage=="logs")refreshLogs() }
    private data class Event(val time:String,val title:String,val detail:String,val category:String,val route:String?)
    private fun event(line:String):Event {
        val time=line.take(8);val message=line.drop(8).trim();val first=message.substringBefore('\n')
        val error=listOf("失败","未确认","超时","失效","中断","拒绝","错误","无法").any{message.contains(it)}
        val category=if(error)"异常" else if(listOf("保护","阈值","重试","监测").any{message.contains(it)})"保护" else "开关"
        val route=Regex("(?:最近路线|确认路线|本次尝试路线)：(局域网|云端)").find(message)?.groupValues?.get(1)
            ?:if(first.startsWith("局域网"))"局域网" else if(first.startsWith("云端"))"云端" else null
        val title=when {
            first.contains("已读回确认插座关闭")->"插座关闭已确认"
            first.contains("已读回确认插座开启")->"插座开启已确认"
            first.contains("本轮 3 次已用尽")&&first.contains("开启")->"插座开启未确认"
            first.contains("本轮 3 次已用尽")->"插座关闭未确认"
            first.startsWith("循环保护 · 插座已确认关闭")->"等待低电量，插座已关闭"
            first.startsWith("循环保护 · 监测中")->"正在监测充电电量"
            else->first.substringBefore('；').take(26)
        }
        return Event(time,title,message,category,route)
    }
    private fun refreshLogs() {
        logFilters.forEach{(name,b)->b.isSelected=name==filter;b.setTextColor(if(name==filter)Color.WHITE else Fluent.ink);b.background=Fluent.surface(activity,if(name==filter)Fluent.blue else Color.WHITE,6,if(name==filter)null else Fluent.line)}
        logList.removeAllViews()
        val visible=entries.map(::event).filter{filter=="全部"||it.category==filter}
        if(visible.isEmpty()) {
            val empty=column().apply{gravity=Gravity.CENTER;setPadding(0,dp(54),0,dp(54))}
            empty.addView(icon("file"),LinearLayout.LayoutParams(dp(44),dp(44)))
            empty.addView(label("暂无${if(filter=="全部")"" else filter}记录",15),LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(14)})
            logList.addView(empty);return
        }
        logList.addView(label("今天",13),LinearLayout.LayoutParams(-1,-2).apply{bottomMargin=dp(15)})
        for((index,e) in visible.withIndex()) {
            val row=LinearLayout(activity).apply{tag="log-event-${e.category}"}
            val timeline=FrameLayout(activity)
            if(index<visible.lastIndex)timeline.addView(View(activity).apply{setBackgroundColor(Fluent.line)},FrameLayout.LayoutParams(dp(1),-1,Gravity.TOP or Gravity.CENTER_HORIZONTAL).apply{topMargin=dp(44)})
            val mark=FrameLayout(activity).apply{background=Fluent.surface(activity,if(e.category=="异常")Color.rgb(255,244,206) else Fluent.soft,10,null)}
            mark.addView(icon(if(e.category=="异常")"info" else if(e.category=="保护")"shield" else "plug",if(e.category=="异常")Color.rgb(138,75,0) else Fluent.blue).apply{tag="log-glyph"},FrameLayout.LayoutParams(dp(22),dp(22),Gravity.CENTER))
            timeline.addView(mark,FrameLayout.LayoutParams(dp(44),dp(44),Gravity.TOP or Gravity.CENTER_HORIZONTAL))
            val copy=column().apply{setPadding(dp(12),0,0,dp(25))}
            copy.addView(LogEntryHeader(activity,e.title,e.time))
            copy.addView(label(e.detail,14).apply{tag="log-detail"},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(4)})
            e.route?.let{copy.addView(label(it,12).apply{background=Fluent.surface(activity,Color.rgb(240,240,240),4,null);setPadding(dp(6),dp(2),dp(6),dp(2))},LinearLayout.LayoutParams(-2,-2).apply{topMargin=dp(7)})}
            row.addView(timeline,LinearLayout.LayoutParams(dp(44),-1));row.addView(copy,LinearLayout.LayoutParams(0,-2,1f));logList.addView(row)
        }
        logList.addView(label("仅保留最近 30 条运行记录",12).apply{gravity=Gravity.CENTER},LinearLayout.LayoutParams(-1,-2).apply{topMargin=dp(10)})
    }
}
