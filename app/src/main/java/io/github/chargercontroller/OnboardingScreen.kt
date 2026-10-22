package io.github.chargercontroller

import android.app.Activity
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.graphics.Typeface
import android.text.Editable
import android.text.InputType
import android.text.TextWatcher
import android.view.Gravity
import android.view.View
import android.widget.*
import io.github.chargercontroller.core.ControlMode
import io.github.chargercontroller.data.OnboardingStore

/** Native counterpart of the approved HTML. Delegates every real operation to MainActivity. */
internal class OnboardingScreen(private val activity: Activity, private val draft: OnboardingStore,
    initial: Model, private val action: (String) -> Unit) {
    data class Device(val index: Int, val name: String, val selected: Boolean)
    data class Model(val connected: Boolean, val verifying: Boolean, val name: String?, val ip: String?,
        val cloudReady: Boolean, val devices: List<Device>, val loaded: Boolean, val listError: String?,
        val notifications: Boolean, val background: Boolean, val resume: Boolean,
        val busy: Boolean, val active: Boolean)
    val root = FrameLayout(activity).apply { tag = "onboarding"; setBackgroundColor(Fluent.canvas); isClickable = true }
    private val scaffold = object : LinearLayout(activity) {
        override fun onMeasure(w: Int, h: Int) {
            super.onMeasure(MeasureSpec.makeMeasureSpec(minOf(MeasureSpec.getSize(w), dp(560)), MeasureSpec.EXACTLY), h)
        }
    }.apply { orientation = LinearLayout.VERTICAL }
    private val header = LinearLayout(activity).apply { gravity = Gravity.CENTER_VERTICAL; setPadding(dp(12), dp(4), dp(12), 0) }
    private val progress = LinearLayout(activity).apply { setPadding(dp(24), dp(8), dp(24), 0) }
    private val body = column().apply { setPadding(dp(24), dp(25), dp(24), dp(24)) }
    private val scroll = ScrollView(activity).apply { tag = "setup-scroll"; isFillViewport = false; addView(body) }
    private val footer = column().apply { setPadding(dp(24), dp(14), dp(24), dp(16)); setBackgroundColor(Color.rgb(250,250,250)) }
    private val message = label("", 12, Fluent.red).apply { tag = "setup-message" }
    private var model = initial
    private var next: Button? = null
    private var low: EditText? = null
    private var high: EditText? = null
    private var range: ThresholdRangeView? = null
    private var validation: TextView? = null
    private var bandNote: TextView? = null
    private var syncing = false
    val page get() = draft.page

    init {
        root.addView(scaffold, FrameLayout.LayoutParams(-1,-1,Gravity.CENTER_HORIZONTAL))
        scaffold.addView(header); scaffold.addView(progress)
        scaffold.addView(scroll, LinearLayout.LayoutParams(-1,0,1f))
        scaffold.addView(View(activity).apply { setBackgroundColor(Fluent.line) }, LinearLayout.LayoutParams(-1,dp(1)))
        scaffold.addView(footer)
        render()
    }
    fun update(value: Model) { if (model != value) { model = value; render(false) } }
    fun go(value: String) { draft.page = value; message.text = ""; render(); scroll.scrollTo(0,0) }
    fun back(): Boolean {
        val index = OnboardingStore.PAGES.indexOf(page)
        if (index <= 0) return false
        go(OnboardingStore.PAGES[index - 1]); return true
    }
    fun showMessage(value: String) { message.text = value; message.setTextColor(if(listOf("失败","无法","失效","无效","未完成","请先","需要","不支持").any { value.contains(it) }) Fluent.red else Fluent.muted); message.visibility = View.VISIBLE }
    private fun dp(n: Int) = Fluent.dp(activity,n)
    private fun column() = LinearLayout(activity).apply { orientation = LinearLayout.VERTICAL }
    private fun label(value: String, size: Int = 13, color: Int = Fluent.muted, bold: Boolean = false) = TextView(activity).apply {
        text = value; textSize = size.toFloat(); setTextColor(color); setLineSpacing(dp(3).toFloat(),1f)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }
    private fun add(parent: LinearLayout, view: View, top: Int = 0) {
        parent.addView(view, LinearLayout.LayoutParams(-1,-2).apply { topMargin = dp(top) })
    }
    private fun button(title: String, key: String, primary: Boolean = false, enabled: Boolean = true) = Button(activity).apply {
        text = title; tag = key; isAllCaps = false; textSize = 14f; minWidth = 0; minimumWidth = 0
        minHeight = dp(48); minimumHeight = dp(48); setPadding(dp(12),dp(8),dp(12),dp(8))
        setTextColor(if(primary) Color.WHITE else Fluent.ink)
        background = Fluent.surface(activity,if(primary) Fluent.blue else Color.WHITE,6,if(primary) null else Color.rgb(209,209,209))
        stateListAnimator = null; isEnabled = enabled && !model.busy && !model.active; alpha = if(isEnabled)1f else .45f
        setOnClickListener { action(key) }
    }
    private fun link(title: String, key: String) = button(title,key).apply { background = null; setTextColor(Fluent.blue); textSize = 12f }
    private fun card(parent: LinearLayout = body) = column().apply {
        setPadding(dp(18),dp(18),dp(18),dp(18)); background = Fluent.surface(activity,Color.WHITE,10)
        add(parent,this,20)
    }
    private fun symbol(parent: LinearLayout, name: String, center: Boolean = true) {
        val mark = FrameLayout(activity).apply { background = Fluent.surface(activity,Fluent.soft,12,null) }
        mark.addView(FluentIcon(activity,name,Fluent.blue),FrameLayout.LayoutParams(dp(28),dp(28),Gravity.CENTER))
        parent.addView(mark,LinearLayout.LayoutParams(dp(60),dp(60)).apply { if(center) gravity=Gravity.CENTER_HORIZONTAL; bottomMargin=dp(16) })
    }
    private fun title(kicker: String, title: String, detail: String) {
        add(body,label(kicker,12,Fluent.blue,true)); add(body,label(title,26,Fluent.ink,true).apply { tag="setup-heading" },10)
        add(body,label(detail,14),8)
    }
    private fun line(parent: LinearLayout, left: String, right: String, color: Int = Fluent.ink) {
        val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(11),0,dp(11)) }
        row.addView(label(left,12),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(label(right,12,color).apply { gravity=Gravity.END;maxWidth=dp(210) })
        parent.addView(row)
    }
    private fun note(text: String, warning: Boolean = false) {
        add(body,label(text,12,if(warning)Color.rgb(116,65,0) else Fluent.muted).apply {
            if(warning) { setPadding(dp(13),dp(12),dp(13),dp(12)); background=Fluent.surface(activity,Color.rgb(255,244,206),6,null) }
        },18)
    }
    private fun primary(title: String, enabled: Boolean = true) {
        next=button(title,"setup-next",true,enabled); add(footer,next!!)
    }
    private fun footerNote(value: String) { add(footer,label(value,11).apply { gravity=Gravity.CENTER },8) }
    private fun render(resetScroll: Boolean = true) {
        val y=scroll.scrollY
        header.removeAllViews();progress.removeAllViews();body.removeAllViews();footer.removeAllViews()
        next=null; low=null; high=null; range=null; validation=null;bandNote=null
        val index=OnboardingStore.PAGES.indexOf(page)
        val back=button("", "setup-back").apply { background=null;contentDescription="返回上一步";visibility=if(index==0)View.INVISIBLE else View.VISIBLE }
        val backFrame=FrameLayout(activity)
        backFrame.addView(FluentIcon(activity,"back"),FrameLayout.LayoutParams(dp(21),dp(21),Gravity.CENTER))
        backFrame.addView(back,FrameLayout.LayoutParams(-1,-1));backFrame.visibility=back.visibility
        header.addView(backFrame,LinearLayout.LayoutParams(dp(44),dp(48)))
        header.addView(label(if(index in 1..4)"首次设置 · $index / 4" else if(index==0)"欢迎使用" else "首次设置",12).apply { gravity=Gravity.CENTER },LinearLayout.LayoutParams(0,-2,1f))
        header.addView(link("稍后设置","setup-skip").apply { visibility=if(index==5)View.INVISIBLE else View.VISIBLE },LinearLayout.LayoutParams(-2,-2))
        progress.visibility=if(index in 1..4)View.VISIBLE else View.GONE
        for(i in 1..4)progress.addView(View(activity).apply { background=Fluent.surface(activity,if(i==index)Fluent.blue else if(i<index)Color.rgb(169,203,234) else Color.rgb(222,222,222),2,null) },LinearLayout.LayoutParams(0,dp(3),1f).apply { if(i>1)leftMargin=dp(5) })
        when(page) {
            "welcome" -> welcome()
            "account" -> account()
            "device" -> devices()
            "rules" -> rules()
            "permissions" -> permissions()
            "complete" -> complete()
        }
        add(body,message,14);message.visibility=if(message.text.isNullOrEmpty())View.GONE else View.VISIBLE
        if(resetScroll)scroll.scrollTo(0,0) else scroll.post { scroll.scrollTo(0,y) }
    }
    private fun welcome() {
        body.addView(OnboardingArtwork(activity),LinearLayout.LayoutParams(-1,dp(168)).apply { bottomMargin=dp(18) })
        add(body,label("先连接插座，\n再设定充电规则。",26,Fluent.ink,true).apply { tag="setup-heading" })
        add(body,label("请先在米家 App 中完成智能插座配对，再回到这里登录并设置充电规则。",14),9)
        feature("plug","连接你的充电插座","支持米家智能插座 3 · ZNCZ301KK")
        feature("sliders","设置电量循环保护","低电量开启，高电量关闭")
        feature("shield","让保护在后台继续","按用途配置通知、后台与开机恢复")
        primary("开始设置");add(footer,link("先看看首页","setup-skip"))
    }
    private fun feature(icon: String, title: String, detail: String) {
        val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(14),0,dp(10)) }
        val mark=FrameLayout(activity).apply { background=Fluent.surface(activity,Color.WHITE,7) }
        mark.addView(FluentIcon(activity,icon),FrameLayout.LayoutParams(dp(20),dp(20),Gravity.CENTER))
        row.addView(mark,LinearLayout.LayoutParams(dp(36),dp(36)).apply { rightMargin=dp(12) })
        val copy=column();add(copy,label(title,13,Fluent.ink,true));add(copy,label(detail,12),4)
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f));add(body,row,5)
    }
    private fun account() {
        title("01 / 连接","连接小米账号","用于获取插座和云端控制凭据。已有设备 IP / Token，也可以直接手动导入。")
        val c=card();symbol(c,if(model.connected)"check" else "user")
        add(c,label(if(model.connected)"小米账号已连接" else "使用手机号与验证码",17,Fluent.ink,true).apply { gravity=Gravity.CENTER })
        add(c,label(if(model.connected)"中国大陆 · 云端会话已保存" else "在小米官方页面完成登录与验证",12).apply { gravity=Gravity.CENTER },8)
        add(c,button(if(model.connected)"重新连接账号" else "前往小米官方页面登录","setup-login",!model.connected),18)
        add(c,label("凭据加密保存 · 不保存账号密码",11).apply { gravity=Gravity.CENTER },14)
        line(body,"米家云服务地区","中国大陆")
        if(model.verifying&&!model.connected) { note("小米身份验证尚未完成。",true);add(body,link("继续身份验证","setup-verify")) }
        add(body,label("已有 IP 和 Token？",12).apply { gravity=Gravity.CENTER },18)
        add(body,link("手动导入，先使用局域网","setup-import"))
        primary("下一步",model.connected||draft.manual&&model.name!=null);footerNote("连接账号或手动导入设备后继续")
    }
    private fun devices() {
        title("02 / 设备","选择目标插座","请选择充电器实际连接的插座。仅显示当前支持控制的设备。")
        if(draft.manual&&model.name!=null) {
            val c=card();line(c,model.name.orEmpty(),"已选择",Fluent.blue);add(c,label("手动导入 · 米家智能插座 3",12));line(c,"局域网 IP",model.ip.orEmpty())
            note("此设备可独立使用局域网。需要云端控制时，再连接账号并从设备列表选择。")
            add(body,link("编辑 IP / Token","setup-import"))
            if(model.connected)add(body,link("从账号选择设备","setup-cloud-list"))
        } else if(!model.connected) {
            val c=card();symbol(c,"plug");add(c,label("先连接账号或导入设备",16,Fluent.ink,true).apply { gravity=Gravity.CENTER })
            add(c,button("连接小米账号","setup-account"),16)
        } else if(model.listError!=null) {
            val c=card();symbol(c,"wifi");add(c,label("暂时无法获取设备",16,Fluent.ink,true));add(c,label(model.listError.orEmpty(),12),9)
            add(c,button("重新尝试","setup-refresh"),16)
        } else if(model.devices.isEmpty()) {
            val c=card();symbol(c,"plug");add(c,label(if(model.loaded)"未找到支持的设备" else if(model.busy)"正在获取设备列表…" else "获取你的设备列表",16,Fluent.ink,true))
            add(c,label("当前仅支持米家智能插座 3\nZNCZ301KK · cuco.plug.v3",12),9)
            add(c,button("重新获取","setup-refresh"),16)
        } else {
            add(body,label("已从中国大陆账号找到 ${model.devices.size} 台支持的设备",12),20)
            for(device in model.devices) {
                val row=LinearLayout(activity).apply {
                    tag="setup-device-${device.index}";gravity=Gravity.CENTER_VERTICAL;setPadding(dp(16),dp(16),dp(16),dp(16))
                    background=Fluent.surface(activity,if(device.selected)Fluent.soft else Color.WHITE,8,if(device.selected)Fluent.blue else Fluent.line)
                    isEnabled=!model.busy&&!model.active;isClickable=true;isFocusable=true
                    contentDescription="${device.name}，米家智能插座 3，${if(device.selected)"已选择" else "未选择"}"
                    setOnClickListener { action("setup-select-${device.index}") }
                }
                row.addView(FluentIcon(activity,"plug",Fluent.blue),LinearLayout.LayoutParams(dp(25),dp(25)).apply { rightMargin=dp(13) })
                val copy=column();add(copy,label(device.name,14,Fluent.ink,true));add(copy,label("米家智能插座 3 · ZNCZ301KK\ncuco.plug.v3",11),6)
                row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
                row.addView(FluentIcon(activity,if(device.selected)"check" else "chevron",if(device.selected)Fluent.blue else Fluent.muted),LinearLayout.LayoutParams(dp(20),dp(20)))
                add(body,row,11)
            }
            note("选择和保存设备不会改变插座开关状态。")
        }
        if(!draft.manual) add(body,link("手动导入 IP / Token","setup-import"),10)
        primary("使用这台插座",model.name!=null&&(draft.manual||model.devices.any { it.selected }))
        footerNote("保存选择不会控制插座开关")
    }
    private fun rules() {
        title("03 / 规则","设置电量循环保护","拖动滑条两端调整范围，也可以输入具体百分比。")
        val c=card();add(c,label("电量阈值",13,Fluent.ink,true))
        line(c,"低电量开启","高电量关闭")
        range=ThresholdRangeView(activity).apply {
            tag="setup-range";values(draft.low.toIntOrNull()?:1,draft.high.toIntOrNull()?:100)
            findViewWithTag<View>("threshold-low-handle")?.tag="setup-low-handle"
            findViewWithTag<View>("threshold-high-handle")?.tag="setup-high-handle"
        }
        c.addView(range,LinearLayout.LayoutParams(-1,dp(48)));line(c,"1%","100%")
        val numbers=LinearLayout(activity)
        fun field(name: String,value: String,key: String): EditText {
            val box=column().apply { setPadding(dp(12),dp(12),dp(12),dp(12));background=Fluent.surface(activity,Color.rgb(250,250,250),7) }
            add(box,label(name,12));val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
            val input=EditText(activity).apply {
                tag=key;contentDescription=name;inputType=InputType.TYPE_CLASS_NUMBER;isSingleLine=true;isSaveEnabled=false
                setText(value);textSize=30f;setTextColor(Fluent.ink);setSelectAllOnFocus(true);setPadding(0,0,0,0)
                isEnabled=!model.busy&&!model.active
            }
            row.addView(input,LinearLayout.LayoutParams(0,dp(52),1f));row.addView(label("%",14))
            add(box,row,8);numbers.addView(box,LinearLayout.LayoutParams(0,-2,1f).apply { if(numbers.childCount>0)leftMargin=dp(12) });return input
        }
        low=field("低电量开启",draft.low,"setup-low");high=field("高电量关闭",draft.high,"setup-high");add(c,numbers,6)
        validation=label("请输入整数：1 ≤ 开启阈值 < 关闭阈值 ≤ 100",12,Fluent.red).apply { tag="setup-rule-error" };add(c,validation!!,10)
        bandNote=label("",12);add(c,bandNote!!,8)
        val watcher=object:TextWatcher {
            override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int)=Unit
            override fun afterTextChanged(e:Editable?)=Unit
            override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int) {
                if(syncing)return
                draft.low=low!!.text.toString();draft.high=high!!.text.toString();updateRange()
            }
        }
        low!!.addTextChangedListener(watcher);high!!.addTextChangedListener(watcher)
        range!!.onValuesChanged={l,h->syncing=true;low!!.setText(l.toString());high!!.setText(h.toString());syncing=false;draft.low=l.toString();draft.high=h.toString();updateRange()}
        val route=card();add(route,label("控制路线",13,Fluent.ink,true))
        val routes=LinearLayout(activity).apply { background=Fluent.surface(activity,Color.rgb(240,240,240),6,null);setPadding(dp(4),dp(4),dp(4),dp(4)) }
        for((mode,name) in listOf(ControlMode.AUTO to "自动",ControlMode.LAN to "局域网",ControlMode.CLOUD to "云端")) {
            val b=button(name,"setup-mode-${mode.name}",false,mode!=ControlMode.CLOUD||model.cloudReady).apply {
                textSize=12f;setTextColor(if(draft.mode==mode)Fluent.blue else Fluent.muted)
                background=if(draft.mode==mode)Fluent.surface(activity,Color.WHITE,4,null) else null;isSelected=draft.mode==mode
            }
            routes.addView(b,LinearLayout.LayoutParams(0,-2,1f))
        }
        add(route,routes,14)
        add(route,label(when(draft.mode){ControlMode.AUTO->if(model.cloudReady)"优先使用局域网，不可达时使用云端备选。" else "先使用局域网；云端备选需有效账号与设备 DID。";ControlMode.LAN->"手机与插座需处于同一局域网，无需云端账号。";ControlMode.CLOUD->"通过小米云控制，需要有效账号会话与设备 DID。"},12),12)
        primary("下一步");footerNote("之后仍可在“保护”页面修改");updateRange()
    }
    private fun updateRange() {
        val l=draft.low.toIntOrNull();val h=draft.high.toIntOrNull();val valid=l!=null&&h!=null&&h in 2..100&&l in 1 until h
        validation?.visibility=if(valid)View.GONE else View.VISIBLE
        next?.isEnabled=valid&&!model.busy&&!model.active;next?.alpha=if(next?.isEnabled==true)1f else .45f
        if(valid) { range?.values(l!!,h!!);bandNote?.text="电量 ≤ $l% 开启，≥ $h% 关闭。两阈值之间，保持插座当前开关状态。" }
        else bandNote?.text="开启阈值需要低于关闭阈值。"
        range?.isEnabled=!model.busy&&!model.active
    }
    private fun permissions() {
        title("04 / 保障","让保护在后台继续","按用途完成授权。你也可以先进入首页，之后再补齐。")
        val c=card()
        permission(c,"bell","保护与结果通知","查看运行状态、停止保护，接收控制失败提醒。",if(model.notifications)"已开启" else "去开启","setup-notifications")
        permission(c,"bolt","后台运行",if(model.background)"电池限制已解除；系统自启动请在系统中确认。" else "建议将电池策略设为无限制，并允许系统自启动。","去设置","setup-background")
        val boot=card();val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL }
        row.addView(label("开机恢复保护",13,Fluent.ink,true),LinearLayout.LayoutParams(0,-2,1f))
        row.addView(FluentToggle(activity).apply { tag="setup-boot";contentDescription="开机恢复保护";checked=model.resume;isEnabled=!model.busy&&!model.active;setOnClickListener { action("setup-boot") } },LinearLayout.LayoutParams(dp(48),dp(44)))
        add(boot,row);add(boot,label("只恢复上次仍在运行的保护；开机首次解锁后执行。还需系统允许自启动。",12),8)
        if(!model.notifications)note("通知尚未开启，暂不能启动保护。\n你仍可完成设置并手动控制插座。",true)
        else note("后台限制因手机系统而异，后续可在“设置 → 通知与后台”继续调整。")
        primary(if(model.notifications)"完成设置" else "先完成，通知稍后开启");footerNote("完成设置不会自动启动保护")
    }
    private fun permission(parent: LinearLayout, icon: String, title: String, detail: String, button: String, key: String) {
        val row=LinearLayout(activity).apply { gravity=Gravity.CENTER_VERTICAL;setPadding(0,dp(9),0,dp(9)) }
        row.addView(FluentIcon(activity,icon),LinearLayout.LayoutParams(dp(20),dp(20)).apply { rightMargin=dp(11) })
        val copy=column();add(copy,label(title,13,Fluent.ink,true));add(copy,label(detail,12),5)
        row.addView(copy,LinearLayout.LayoutParams(0,-2,1f))
        row.addView(this.button(button,key).apply { textSize=12f;setPadding(dp(8),dp(6),dp(8),dp(6)) },LinearLayout.LayoutParams(-2,-2).apply { leftMargin=dp(8) })
        parent.addView(row)
    }
    private fun complete() {
        symbol(body,"check");add(body,label("设置已保存",26,Fluent.ink,true).apply { tag="setup-heading";gravity=Gravity.CENTER })
        add(body,label("设备和保护规则已经准备好。",14).apply { gravity=Gravity.CENTER },10)
        val c=card();line(c,"目标插座",model.name?:"尚未选择");line(c,"电量循环保护","${draft.low}% 开启 / ${draft.high}% 关闭")
        line(c,"控制路线",when(draft.mode){ControlMode.AUTO->"自动 · 局域网优先";ControlMode.LAN->"仅局域网";ControlMode.CLOUD->"仅云端"})
        line(c,"保护通知",if(model.notifications)"已开启" else "待开启",if(model.notifications)Color.rgb(16,124,16) else Color.rgb(138,75,0))
        line(c,"开机恢复保护",if(model.resume)"已开启" else "已关闭")
        add(body,label("保护尚未启动",15,Fluent.ink,true).apply { tag="setup-not-started";gravity=Gravity.CENTER },22)
        add(body,label("进入首页后，由你决定何时开始。",12).apply { gravity=Gravity.CENTER },8)
        if(!model.notifications)note("开始保护前，还需要开启保护与结果通知。",true)
        primary("进入首页");add(footer,link("返回检查规则","setup-review-rules"))
    }
}

/** Thin-line phone/socket illustration drawn locally, matching the welcome HTML. */
internal class OnboardingArtwork(activity: Activity) : View(activity) {
    private val paint=Paint(Paint.ANTI_ALIAS_FLAG)
    private val rect=RectF()
    init { importantForAccessibility=IMPORTANT_FOR_ACCESSIBILITY_NO }
    override fun onDraw(c: Canvas) {
        super.onDraw(c)
        c.save();val scale=minOf(width/306f,height/170f);c.translate((width-306*scale)/2,(height-170*scale)/2);c.scale(scale,scale)
        fun line(x:Float,y:Float,x2:Float,y2:Float,color:Int=Color.rgb(183,183,183),stroke:Float=1.5f) { paint.color=color;paint.style=Paint.Style.STROKE;paint.strokeWidth=stroke;paint.strokeCap=Paint.Cap.ROUND;c.drawLine(x,y,x2,y2,paint) }
        fun box(l:Float,t:Float,r:Float,b:Float,radius:Float,color:Int,stroke:Boolean=false) { rect.set(l,t,r,b);paint.color=color;paint.style=if(stroke)Paint.Style.STROKE else Paint.Style.FILL;paint.strokeWidth=1.5f;c.drawRoundRect(rect,radius,radius,paint) }
        line(82f,145f,82f,155f);line(82f,155f,231f,155f);line(231f,155f,231f,128f)
        box(46f,10f,126f,145f,12f,Color.WHITE);box(46f,10f,126f,145f,12f,Color.rgb(183,183,183),true)
        line(74f,18f,98f,18f,Fluent.line,3f);box(56f,37f,116f,112f,6f,Fluent.soft)
        box(74f,70f,94f,86f,1f,Fluent.blue,true);line(80f,66f,88f,66f,Fluent.blue,2f)
        line(86f,72f,82f,78f,Fluent.blue);line(82f,78f,87f,78f,Fluent.blue);line(87f,78f,83f,84f,Fluent.blue)
        line(73f,125f,100f,125f,Fluent.line,3f)
        box(196f,50f,268f,128f,12f,Color.WHITE);box(196f,50f,268f,128f,12f,Color.rgb(183,183,183),true)
        line(219f,72f,219f,83f,Fluent.muted,3.5f);line(244f,72f,244f,83f,Fluent.muted,3.5f);line(232f,98f,232f,108f,Fluent.muted,3.5f)
        paint.style=Paint.Style.FILL;paint.color=Fluent.blue;c.drawCircle(232f,118f,2.5f,paint)
        line(141f,79f,176f,79f,Fluent.blue);line(171f,74f,176f,79f,Fluent.blue);line(176f,79f,171f,84f,Fluent.blue);line(144f,94f,164f,94f)
        c.restore()
    }
}
